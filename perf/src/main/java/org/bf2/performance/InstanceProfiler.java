package org.bf2.performance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.openmessaging.benchmark.TestResult;
import io.openmessaging.benchmark.Workload;
import io.openmessaging.benchmark.utils.distributor.KeyDistributorType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.common.SuppressFBWarnings;
import org.bf2.operator.operands.KafkaInstanceConfiguration;
import org.bf2.operator.operands.KafkaInstanceConfiguration.Kafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacity;
import org.bf2.performance.TestUtils.AvailableResources;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.test.Environment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 * issues seen so far
 * - some threads hang around even after teardown
 * - 99th percentile testing latencies are not consistent, which makes the aggregate unreliable.  More warm-up? throw out outliers? switch to 95th?
 *
 * TODO:
 * - tune test durations
 * - something other than target dir
 * - document m5.2xlarge 9 node client cluster expectation
 * - we ran as a one off, but we could perform a test run with clients lagging beyond the page cache to confirm latency
 * - some qualification of network performance
 * - account for disk vs ssd in page cache assumptions
 * -- currently we don't have the ability to create ssd clusters
 */
public class InstanceProfiler {

    public enum Step {
        SETUP,
        SIZE,
        LATENCY,
        THROUGHPUT,
        PARTITIONS,
        CONSUMERS,
        PRODUCERS,
        DONE
    }

    public enum Storage {
        GP2("10Gi"), GP3("223Gi");

        Storage(String zookeeperSize) {
            this.zookeeperSize = zookeeperSize;
        }

        final String zookeeperSize;
    }

    public enum Profile {
        // recommended latency optimized profile
        LATENCY("acks=1\n", "auto.offset.reset=earliest\nenable.auto.commit=false\n",
                "min.insync.replicas=2\ncompression.type=uncompressed\ncleanup.policy=delete\nretention.ms=240000\n"),
        // recommended latency optimized profile, but with batching disabled for throughput testing
        LATENCY_NO_BATCHING("acks=1\nbatch.size=0\n", "auto.offset.reset=earliest\nenable.auto.commit=false\n",
                "min.insync.replicas=2\ncompression.type=uncompressed\ncleanup.policy=delete\nretention.ms=240000\n"),
        // using both https://www.confluent.io/blog/configure-kafka-to-minimize-latency/
        // and https://docs.confluent.io/cloud/current/client-apps/optimizing/throughput.html#optimizing-for-throughput
        THROUGHPUT("acks=1\nbatch.size=200000\nlinger.ms=100\n",
                "fetch.min.bytes=100000\nauto.offset.reset=earliest\nenable.auto.commit=false\n",
                "min.insync.replicas=2\ncompression.type=uncompressed\ncleanup.policy=delete\nretention.ms=240000\n");

        final String producerConfig;
        final String consumerConfig;
        final String topicConfig;
        // must be changed together - and we may adapt the logic to use a spectrum of values
        final int messageSize;
        final String payloadFile;

        Profile(String producerConfig, String consumerConfig, String topicConfig) {
            this(producerConfig, consumerConfig, topicConfig, 1024, "src/test/resources/payload/payload-1Kb.data");
        }

        Profile(String producerConfig, String consumerConfig, String topicConfig, int messageSize, String payloadFile) {
            this.producerConfig = producerConfig;
            this.consumerConfig = consumerConfig;
            this.topicConfig = topicConfig;
            this.messageSize = messageSize;
            this.payloadFile = payloadFile;
        }
    }

    @SuppressFBWarnings
    public static class ThroughputResult {
        public double averageMaxProducerMBs;
        public double averageMaxConsumerMBs;
        public double medianMaxProducerMBs;
        public double medianMaxConsumerMBs;
    }

    @JsonInclude(content = JsonInclude.Include.NON_NULL)
    @SuppressFBWarnings
    public static class LatencyResult {
        public Double aggregatedEndToEndLatency50pct;
        public Double medianEndToEndLatency99pct;
        public Double aggregatedPublishLatency50pct;
        public Double aggregatedPublishLatency99pct;
        public String error;
        public Double maxConnectionCount;
    }

    @JsonInclude(content = JsonInclude.Include.NON_NULL)
    @SuppressFBWarnings
    public static class StreamingUnitResult {
        public ThroughputResult throughputResult;
        public LatencyResult latency;
    }

    // TODO will need to hold all of the instance state as well to ensure we're continuing the same test
    public static class ProfilingResult {
        public Step completedStep;

        public String name;

        /*
         * test setup
         */
        public String ombNodeType;
        public String kafkaNodeType;
        public int ombWorkerNodes;
        public Quantity ombWorkerCpu;
        public Quantity ombWorkerMemory;

        public KafkaInstanceConfiguration config;
        public ManagedKafkaCapacity capacity;

        public LatencyResult baselineLatency;

        public StreamingUnitResult streamingUnit;

        public TreeMap<Integer, LatencyResult> partitionResults = new TreeMap<>();

        public Map<Profile, ThroughputResult> throughputResults = new HashMap<>();

        // connections
        public TreeMap<Integer, LatencyResult> consumers = new TreeMap<>();
        public TreeMap<Integer, LatencyResult> consumerGroups = new TreeMap<>();
        public TreeMap<Integer, LatencyResult> producers = new TreeMap<>();
    }

    private static final Logger LOGGER = LogManager.getLogger(InstanceProfiler.class);

    static final long ONE_MB = 1024*1024;
    static final long ONE_GB = ONE_MB*1024;
    static final long MAX_KAFKA_VM_SIZE = ONE_GB*6; // https://docs.confluent.io/platform/current/kafka/deployment.html#memory
    static final long MIN_BROKER_VM_SIZE = ONE_GB/2;

    /*
     * Input test state that may eventually be externalized
     */
    final boolean collocateBrokerWithZookeeper = true;
    final double lowThroughput = 30*ONE_MB;
    final int replicationFactor = 3;
    final int numberOfBrokers = 3;
    /* see https://www.confluent.io/blog/kafka-fastest-messaging-system/#test-setup where they chose 100
     *
     * and from https://www.confluent.io/blog/configure-kafka-to-minimize-latency/
     * "Purely from a throughput perspective, you should be able to get the full throughput from a Kafka cluster with an order of 10 partitions per broker."
     *
     * However for baselining higher partitions are not necessary or counterproductive, so we'll start with numberOfBrokers*3
     */
    final int nominalPartitionCount = numberOfBrokers*3;
    Storage storage = Storage.GP2;
    // if !autoSize, use the default configuration values
    private boolean autoSize = true;

    /*
     * primary output state
     */
    ProfilingResult profilingResult = new ProfilingResult();

    /*
     * working state
     */
    ManagedKafkaProvisioner kafkaProvisioner;
    KubeClusterResource kafkaCluster;
    OMB omb;
    File logDir;
    String instanceBootstrap;
    boolean installedProvisioner;

    // streaming unit definition - to be externalized
    static int MAX_CONNECTIONS = 2000;
    static int INGRESS=50;
    static int EGRESS_MULTIPLE=2;

    public static void main(String[] args) throws Exception {
        InstanceProfiler profiler = new InstanceProfiler();
        try {
            profiler.setup();
            profiler.profile();
            //profiler.runLocalTest();
        } catch (Throwable t) {
            LOGGER.error("Uncaught exception", t);
        } finally {
            if (profiler.profilingResult.completedStep == Step.DONE) {
                // don't tear down for now to keep reusing the cluster
                //profiler.teardown();
            }
        }
    }

    /*private void runLocalTest() throws Exception {
        Profile profile = Profile.LATENCY;
        sizeInstance();
        deployIfNeeded("profile");
        OMBDriver driver = createDriver(instanceBootstrap, profile);

        byte[] storeBytes = Base64.getDecoder().decode(kafkaProvisioner.getTlsConfig().getTrustStoreBase64());
        File store = new File("target", "listener.jks");
        Files.write(store.toPath(), storeBytes);
        driver.setCommonConfig(driver.getCommonConfig().replace("/cert/listener.jks", store.getAbsolutePath()));
        OMBWorkload load = createBasicWorkload(profile.messageSize*900, 1, profile);
        load.warmupDurationMinutes = 2;
        load.testDurationMinutes = 3;
        load.subscriptionsPerTopic = 0;
        load.producersPerTopic = 2;
        load.consumerPerSubscription = 0;
        load.producerRate = 1;
        // could bypass omb altogether
        omb.runWorkload(new File("target"), driver, Collections.emptyList(), load);
    }*/

    private void teardown() throws Exception {
        if (omb != null) {
            omb.uninstall();
            kafkaProvisioner.uninstall();
            kafkaProvisioner.teardown();
        }
    }

    private void setup() throws Exception {
        readResults();
        if (profilingResult.name == null) {
            profilingResult.name = "profile-" + Environment.DATE_FORMAT.format(LocalDateTime.now());
        }

        logDir = new File("target", profilingResult.name);
        Files.createDirectories(logDir.toPath());

        kafkaCluster = KubeClusterResource.connectToKubeCluster(PerformanceEnvironment.KAFKA_KUBECONFIG);
        profilingResult.kafkaNodeType = kafkaCluster.getWorkerNodes().get(0).getMetadata().getLabels().get("node.kubernetes.io/instance-type");
        kafkaProvisioner = ManagedKafkaProvisioner.create(kafkaCluster);

        kafkaProvisioner.setup();
        omb = new OMB(KubeClusterResource.connectToKubeCluster(PerformanceEnvironment.OMB_KUBECONFIG));

        omb.install(kafkaProvisioner.getTlsConfig());

        // TODO: if there is an existing result, make sure it's the same test setup

        profilingResult.ombNodeType = omb.getOmbCluster().getWorkerNodes().get(0).getMetadata().getLabels().get("node.kubernetes.io/instance-type");
        profilingResult.ombWorkerNodes = omb.getOmbCluster().getWorkerNodes().size();

        AvailableResources resources = getMinAvailableResources(omb.getOmbCluster().getWorkerNodes().stream());

        // use all available resources on the worker nodes with 2 workers per node
        //if (resources.memoryBytes > 16*ONE_GB || resources.memoryBytes < 8*ONE_GB) {
            //throw new IllegalStateException("Client instance types are expected to have 16 GB");
        //}

        // assume instead resources that will fit on 2xlarge or xlarge
        resources.cpuMillis = Math.min(6400, resources.cpuMillis);
        resources.memoryBytes = Math.min(12*ONE_GB, resources.memoryBytes);

        omb.setWorkerCpu(Quantity.parse(resources.cpuMillis / 2 + "m"));
        omb.setWorkerContainerMemory(Quantity.parse(String.valueOf(resources.memoryBytes / 2)));

        profilingResult.ombWorkerCpu = omb.getWorkerCpu();
        profilingResult.ombWorkerMemory = omb.getWorkerContainerMemory();

        LOGGER.info("OMB Workers will use {} cpu and {} memory requests", omb.getWorkerCpu(), omb.getWorkerContainerMemory());

        if (profilingResult.completedStep == null) {
            installedProvisioner = true;
            kafkaProvisioner.install();
            writeResults(Step.SETUP);
        }
    }

    private AvailableResources getMinAvailableResources(Stream<Node> stream) {
        AvailableResources resources = stream.map(TestUtils::getMaxAvailableResources)
                .reduce((a1, a2) -> {
                    a1.cpuMillis = Math.min(a1.cpuMillis, a2.cpuMillis);
                    a1.memoryBytes = Math.min(a1.memoryBytes, a2.memoryBytes);
                    return a1;
                }).get();
        return resources;
    }

    /**
     * https://issues.redhat.com/browse/MGDSTRM-3853?focusedCommentId=16399387&page=com.atlassian.jira.plugin.system.issuetabpanels%3Acomment-tabpanel#comment-16399387
     *
     * Profiling an instance is a series of steps that narrow in on recommended settings and performance characteristics.
     */
    protected void profile() throws Exception {
        // TODO: could be replaced by a switch / statemachine if steps repeat

        if (profilingResult.completedStep == Step.SETUP) {
            sizeInstance();
            writeResults(Step.SIZE);
        }

        deployIfNeeded("profile");

        if (profilingResult.completedStep == Step.SIZE) {
            profilingResult.baselineLatency = determineLatency(workload -> {
                workload.partitionsPerTopic = nominalPartitionCount;
                workload.name = "latency-baseline";
            });
            // TODO there is a latency value that is potentially relevant at the sustainable throughput,
            // but we're not looking for that here - nor in determineThroughput just yet
            writeResults(Step.LATENCY);
        }

        if (profilingResult.completedStep == Step.LATENCY) {
            determineThroughput(Profile.LATENCY_NO_BATCHING);
            determineThroughput(Profile.THROUGHPUT);
            streamingUnitTest();
            writeResults(Step.THROUGHPUT);
        }

        if (profilingResult.completedStep == Step.THROUGHPUT) {
            determinePartitions();
            writeResults(Step.PARTITIONS);
        }

        if (profilingResult.completedStep == Step.PARTITIONS) {
            determineProducers();
            writeResults(Step.PRODUCERS);
        }

        if (profilingResult.completedStep == Step.PRODUCERS) {
            determineConsumersPerConsumerGroup();
            // this should be more of a bandwidth than a connection test
            //determineConsumerGroups();

            // TODO use the maxConsumerGroups * consumers/partitions + maxProducer connections
            // to validate a single max connections

            writeResults(Step.CONSUMERS);
        }

        writeResults(Step.DONE);

        LOGGER.info("Done running");
    }

    private void deployIfNeeded(String name) throws Exception {
        ManagedKafka mk = null;
        Resource<ManagedKafka> mkResource = kafkaCluster.kubeClient().client().resources(ManagedKafka.class).inNamespace(Constants.KAFKA_NAMESPACE).withName(name);
        try {
            mk = mkResource.get();
        } catch (KubernetesClientException e) {

        }

        ManagedKafkaDeployment kd = null;
        if (mk == null) {
            if (!installedProvisioner) {
                // TODO: come up with a better resume logic here - it currently has to recreate everything
                installedProvisioner = true;
                kafkaProvisioner.install();
            }
            kafkaProvisioner.removeClusters(true);
            kd = kafkaProvisioner.deployCluster(name, profilingResult.capacity, profilingResult.config);
        } else {
            // validate config / capacity
            kd = new ManagedKafkaDeployment(mk, kafkaCluster);
            kd.start();
        }
        instanceBootstrap = kd.waitUntilReady();
    }

    protected LatencyResult determineLatency(Consumer<Workload> setupModifier) throws Exception {
        return determineLatency(setupModifier, null);
    }

    protected LatencyResult determineLatency(Consumer<Workload> setupModifier, BiConsumer<Profile, TestResult> resultConsumer) throws Exception {
        Profile profile = Profile.LATENCY;
        // find best-case latency at a "low throughput"
        int pub = Math.min(nominalPartitionCount, 2*profilingResult.ombWorkerNodes);
        OMBWorkload load = createBasicWorkload(lowThroughput, pub, profile);
        load.warmupDurationMinutes = 2;
        load.testDurationMinutes = 4;
        setupModifier.accept(load);
        LatencyResult result = new LatencyResult();

        try {
            OMBWorkloadResult loadResult = doTestRun(load.name, load, profile);
            TestResult loadTestResult = loadResult.getTestResult();
            double avgPublishRate = TestUtils.getAvg(loadTestResult.publishRate);
            double avgPublishThrougput = avgPublishRate*profile.messageSize;

            if (!isThroughputAcceptable(load, loadTestResult)) {
                throw new IllegalStateException("latency result not acceptable due to throughput");
            }
            result.aggregatedEndToEndLatency50pct = loadTestResult.aggregatedEndToEndLatency50pct;
            // the 99% tile can be very noisy, we'll use the median instead of the aggregated value.  This will
            // always be significantly higher than the 95%, but not truly reflect the 99% - however we are not yet able to
            result.medianEndToEndLatency99pct = TestUtils.getMedian(loadTestResult.endToEndLatency99pct);
            result.aggregatedPublishLatency50pct = loadTestResult.aggregatedPublishLatency50pct;
            result.aggregatedPublishLatency99pct = loadTestResult.aggregatedPublishLatency99pct;
            result.maxConnectionCount = loadTestResult.connectionCount.stream().max(Double::compareTo).get();

            if (resultConsumer != null) {
                resultConsumer.accept(profile, loadTestResult);
            }

            // TODO validate low throttle / queue / consumer latency
            // TODO a warning if samples are too far off the median

            LOGGER.info(String.format("Result summary for %s consumers / %s partitions latency %,.2f MB/s e2e latency 50: %,.2f median 99: %,.2f 99: %,.2f",
                    pub, load.partitionsPerTopic, avgPublishThrougput / ONE_MB, loadTestResult.aggregatedEndToEndLatency50pct, result.medianEndToEndLatency99pct,
                    loadTestResult.aggregatedEndToEndLatency99pct));
        } catch (Exception e) {
            result.error = e.getMessage();
            LOGGER.info(String.format("not accepting %s as there were errors during the test", load.name), e);
        }
        return result;
    }

    /**
     * Determine throughput
     * see https://www.confluent.io/blog/kafka-fastest-messaging-system/#throughput-test
     */
    protected void determineThroughput(Profile profile) throws Exception {
        int pub = Math.min(nominalPartitionCount, 2*profilingResult.ombWorkerNodes);

        // start by over producing to get a better guess at producer rate
        // this should also use up burst credits... there's no great way of accounting for this
        OMBWorkload load = createBasicWorkload(10*ONE_GB, pub, profile);
        load.warmupDurationMinutes = 3;
        load.testDurationMinutes = 4;

        OMBWorkloadResult loadResult = doTestRun(String.format("throughput-%s-0", profile), load, profile);
        ThroughputResult throughputResult = toThroughputResult(profile, loadResult.getTestResult());

        // TODO could include some text output about the average, median, stddev, etc.

        LOGGER.info(String.format("Result summary for %s %s producers/consumers %s", profile, pub, Serialization.asYaml(throughputResult)));

        profilingResult.throughputResults.put(profile, throughputResult);

        // could run again for confirmation - or take multiple samples
        // there could also be a notion of a sustained accounting for latency
        // or a burst looking over a shorter window - which could be from the test ouptut
    }

    private ThroughputResult toThroughputResult(Profile profile, TestResult loadTestResult) {
        double avgPublishRate = TestUtils.getAvg(loadTestResult.publishRate);
        double avgConsumeRate = TestUtils.getAvg(loadTestResult.consumeRate);
        double medianPublishRate = TestUtils.getMedian(loadTestResult.publishRate);
        double medianConsumeRate = TestUtils.getMedian(loadTestResult.consumeRate);

        ThroughputResult throughputResult = new ThroughputResult();
        throughputResult.averageMaxProducerMBs = avgPublishRate*profile.messageSize / ONE_MB;
        throughputResult.averageMaxConsumerMBs = avgConsumeRate*profile.messageSize / ONE_MB;
        throughputResult.medianMaxProducerMBs = medianPublishRate*profile.messageSize / ONE_MB;
        throughputResult.medianMaxConsumerMBs = medianConsumeRate*profile.messageSize / ONE_MB;
        return throughputResult;
    }

    protected void streamingUnitTest() throws Exception {
        profilingResult.streamingUnit = new StreamingUnitResult();
        profilingResult.streamingUnit.latency = determineLatency(workload -> {
            workload.producerRate = (int) (INGRESS*(numberOfBrokers/3*ONE_MB) / workload.messageSize);
            workload.subscriptionsPerTopic = EGRESS_MULTIPLE;
            workload.name = "streaming-unit";
            workload.warmupDurationMinutes = 2;
            workload.testDurationMinutes = 10;
        }, (profile, result) -> {profilingResult.streamingUnit.throughputResult = toThroughputResult(profile, result);});
    }

    private void writeResults(Step step) throws IOException {
        profilingResult.completedStep = step;
        Files.write(new File("target", "result.yaml").toPath(), Serialization.asYaml(profilingResult).getBytes(StandardCharsets.UTF_8));
    }

    private void readResults() throws IOException {
        File file = new File("target", "result.yaml");
        if (file.exists()) {
            profilingResult = Serialization.unmarshal(Files.readString(file.toPath()), ProfilingResult.class);
        }
    }

    int density = 1;

    protected void sizeInstance() throws Exception {
        Stream<Node> workerNodes = kafkaCluster.getWorkerNodes().stream();
        if (!collocateBrokerWithZookeeper){
            kafkaProvisioner.validateClusterForBrokers(numberOfBrokers, false, workerNodes);
            workerNodes = kafkaCluster.getWorkerNodes().stream().filter(n -> n.getSpec().getTaints().stream()
                        .anyMatch(t -> t.getKey().equals(ManagedKafkaProvisioner.KAFKA_BROKER_TAINT_KEY)));
        }

        // note these number seem to change per release - 4.9 reports a different allocatable, than 4.8
        AvailableResources resources = getMinAvailableResources(workerNodes);
        long cpuMillis = resources.cpuMillis;
        long memoryBytes = resources.memoryBytes;

        Properties p = new Properties();
        try (InputStream is = InstanceProfiler.class.getResourceAsStream("/application.properties")) {
            p.load(is);
        }
        KafkaInstanceConfiguration defaults = Serialization.jsonMapper().convertValue(p, KafkaInstanceConfiguration.class);

        // when locating with ZK, then reduce the available resources accordingly
        if (collocateBrokerWithZookeeper){
            // earlier code making a guess at the page cache size has been removed - until we can more reliably detect it's effect
            // there's no point in making a trade-off between extra container memory and JVM memory
            // TODO: could choose a memory size where we can fit even multiples of zookeepers
            long zookeeperBytes = Quantity.getAmountInBytes(Quantity.parse(defaults.getZookeeper().getContainerMemory())).longValue();
            long zookeeperCpu = Quantity.getAmountInBytes(Quantity.parse(defaults.getZookeeper().getContainerCpu())).movePointRight(3).longValue();

            List<Long> additionalPodCpu = new ArrayList<>();
            List<Long> additionalPodMemory = new ArrayList<>();

            additionalPodCpu.add(Quantity.getAmountInBytes(Quantity.parse(defaults.getCanary().getContainerCpu())).movePointRight(3).longValue());
            additionalPodMemory.add(Quantity.getAmountInBytes(Quantity.parse(defaults.getCanary().getContainerMemory())).longValue());

            additionalPodCpu.add(Quantity.getAmountInBytes(Quantity.parse(defaults.getAdminserver().getContainerCpu())).movePointRight(3).longValue());
            additionalPodMemory.add(Quantity.getAmountInBytes(Quantity.parse(defaults.getAdminserver().getContainerMemory())).longValue());

            additionalPodCpu.add(Quantity.getAmountInBytes(Quantity.parse(defaults.getExporter().getContainerCpu())).movePointRight(3).longValue());
            additionalPodMemory.add(Quantity.getAmountInBytes(Quantity.parse(defaults.getExporter().getContainerMemory())).longValue());

            LOGGER.info("Total overhead of additional pods {} memory, {} cpu",
                    additionalPodMemory.stream().collect(Collectors.summingLong(Long::valueOf)),
                    additionalPodCpu.stream().collect(Collectors.summingLong(Long::valueOf)));

            // actual needs ~ 800Mi and 1575m cpu over 3 nodes, but worst case is over two. amountNeeded will
            // estimate that in a more targeted way - but still simplified
            memoryBytes = resources.memoryBytes - density*(zookeeperBytes + amountNeeded(additionalPodMemory));
            cpuMillis = resources.cpuMillis - density*(zookeeperCpu + amountNeeded(additionalPodCpu));
        }

        // reserve additional memory to help lessen the fluctuation of resources across openshift versions
        // and if there are eventually pods that need to be collocated, and we don't want to adjust the resources downward
        if (density == 1) {
            memoryBytes -= 2*ONE_GB;
            cpuMillis -= 500;
        } else {
            // we can assume a much tighter resource utilization for density 2 - it can fluctuate between releases
            // or may require adjustments as other pods are added or pod resource adjustments are made
            memoryBytes -= 1*ONE_GB;
            cpuMillis -= 200;
        }

        memoryBytes = memoryBytes/density;
        cpuMillis = cpuMillis/density;

        long maxVmBytes = Math.min(memoryBytes - getVMOverheadForContainer(memoryBytes), MAX_KAFKA_VM_SIZE);

        if (density > 1) {
            maxVmBytes -= 1*ONE_GB;
        }

        if (!autoSize) {
            long defaultMemory = Quantity.getAmountInBytes(Quantity.parse(defaults.getKafka().getContainerMemory())).longValue();
            long defaultCpu = Quantity.getAmountInBytes(Quantity.parse(defaults.getKafka().getContainerCpu())).movePointRight(3).longValue();
            long defaultMaxVmBytes = Quantity.getAmountInBytes(Quantity.parse(defaults.getKafka().getJvmXms())).longValue();

            LOGGER.info("Calculated kafka sizing {} container memory, {} container cpu, and {} vm memory", memoryBytes, cpuMillis, maxVmBytes);

            memoryBytes = defaultMemory;
            cpuMillis = defaultCpu;
            maxVmBytes = defaultMaxVmBytes;
        }

        KafkaInstanceConfiguration toUse = new KafkaInstanceConfiguration();
        AdopterProfile.openListenersAndAccess(toUse);
        toUse.getKafka().setReplicas(numberOfBrokers);
        toUse.getKafka().setContainerCpu(cpuMillis+"m");
        toUse.getKafka().setJvmXms(String.valueOf(maxVmBytes));
        toUse.getKafka().setContainerMemory(String.valueOf(memoryBytes));

        profilingResult.config = toUse;

        profilingResult.config.getKafka().setColocateWithZookeeper(collocateBrokerWithZookeeper);

        profilingResult.config.getKafka().setMaxConnections(Integer.MAX_VALUE);
        profilingResult.config.getKafka().setConnectionAttemptsPerSec(Integer.MAX_VALUE);

        profilingResult.config.getKafka().setStorageClass(storage.name().toLowerCase());
        profilingResult.config.getZookeeper().setVolumeSize(storage.zookeeperSize);

        // once we make the determination, create the instance
        profilingResult.capacity = kafkaProvisioner.defaultCapacity(40_000_000); // not used as quota is turned off
        profilingResult.capacity.setMaxDataRetentionSize(Quantity.parse((1000 * numberOfBrokers/3) + "Gi"));

        Kafka kafka = profilingResult.config.getKafka();
        LOGGER.info("Running with kafka sizing {} container memory, {} container cpu, and {} vm memory", kafka.getContainerMemory(), kafka.getContainerCpu(), kafka.getJvmXms());

        // if running on m5.4xlarge or greater and want to constrain resources like m5.2xlarge (fully dedicated)
        //profilingResult.config.getKafka().setContainerMemory("29013426176");
        //profilingResult.config.getKafka().setContainerCpu("6500m");

        // to constrain resources like m5.xlarge (fully dedicated)
        //profilingResult.config.getKafka().setContainerMemory("12453740544");
        //profilingResult.config.getKafka().setContainerCpu("2500m");
    }

    /**
     * Computes the amount needed assuming that the values will be spread across two nodes
     * @param additionalPodCpu
     */
    private long amountNeeded(List<Long> resources) {
        Stream<Long> sorted = resources.stream().sorted(Comparator.reverseOrder());
        long sum = resources.stream().collect(Collectors.summingLong(Long::valueOf));
        Iterator<Long> iter = sorted.iterator();
        long running = iter.next();
        while (running < sum/2) {
            running += iter.next();
        }
        return running;
    }

    /**
     * see basic testing on https://cwiki.apache.org/confluence/display/KAFKA/KIP-578%3A+Add+configuration+to+limit+number+of+partitions
     * within some assumed high / low bounds and a wider tolerance on acceptable result, we iterate to find the likely number of partitions
     * beyond which performance degrades to an unacceptable amount.
     *
     * - the degradation is somewhat smooth so this is an arbitrary cutoff
     * - do not see the same performance curve as the kip, that is throughput remains high even for a higher number of partitions
     *   for a fixed number of consumers -- were they scaling the consumers as well?
     */
    protected void determinePartitions() throws Exception {
        List<Integer> samples = Arrays.asList(100, 750, 1500, 2000);
        int multiplier = numberOfBrokers/3;
        for (int sample : samples.stream().map(s -> s*multiplier).collect(Collectors.toList())) {
            try {
                LOGGER.info("Running latency test for {} partitions", sample);
                profilingResult.partitionResults.put(sample, determineLatency(workload -> {
                    workload.partitionsPerTopic = sample;
                    workload.name = "latency-partitions-" + sample;
                }));
            } catch (IllegalStateException e) {
                LOGGER.info(String.format("not accepting %s as there were errors during the test", sample), e);
            }
        }
    }

    private void determineConsumersPerConsumerGroup() throws Exception {
        // this is not expected to vary with the number of brokers are every consumer in the consumer group uses a single
        // broker for coordination
        List<Integer> samples = Arrays.asList(100, 250, 500);
        for (int sample : samples) {
            LOGGER.info("Running latency test for {} consumers", sample);
            profilingResult.consumers.put(sample, determineLatency(workload -> {
                workload.consumerPerSubscription = sample;
                workload.partitionsPerTopic = sample;
                workload.name = "latency-consumers-" + sample;
            }));
        }
    }

    private void determineConsumerGroups() throws Exception {
        List<Integer> samples = Arrays.asList(10, 25);
        int multiplier = numberOfBrokers/3;
        for (int sample : samples.stream().map(s -> s*multiplier).collect(Collectors.toList())) {
            LOGGER.info("Running latency test for {} consumer groups", sample);
            profilingResult.consumerGroups.put(sample, determineLatency(workload -> {
                workload.subscriptionsPerTopic = sample;
                workload.name = "latency-consumergroups-" + sample;
            }));
        }
    }

    private void determineProducers() throws Exception {
        int maxClients = MAX_CONNECTIONS*(numberOfBrokers/3)/(numberOfBrokers+1);
        List<Integer> samples = Arrays.asList(2*(maxClients/5), maxClients, (maxClients*3)/2);
        for (int sample : samples) {
            LOGGER.info("Running latency test for {} producers", sample);
            profilingResult.producers.put(sample, determineLatency(workload -> {
                workload.producersPerTopic = sample;
                workload.name = "latency-producers-" + sample;
            }));
        }
    }

    protected boolean isThroughputAcceptable(OMBWorkload load, TestResult loadTestResult) {
        boolean consumerThroughputFailed = loadTestResult.consumeRate.stream().anyMatch(r -> r < .9*load.producerRate*load.subscriptionsPerTopic);
        if (consumerThroughputFailed) {
            LOGGER.info("consumers could not keep up with target rate");
        }
        boolean producerThroughputFailed = loadTestResult.publishRate.stream().anyMatch(r -> r < .9*load.producerRate);
        if (producerThroughputFailed) {
            LOGGER.info("producers could not keep up with target rate");
        }
        return !consumerThroughputFailed && !producerThroughputFailed;
    }

    /**
     * see https://www.eclipse.org/openj9/docs/xxusecontainersupport/ for assumptions
     */
    static long getVMOverheadForContainer(long containerBytes) {
        long overhead = 0;
        if (containerBytes > 2*ONE_GB) {
            overhead = containerBytes/4;
        } else if (containerBytes > ONE_GB) {
            overhead = ONE_GB / 2;
        } else {
            overhead = containerBytes/2;
        }
        return overhead;
    }

    /**
     * Create a basic workload suitable to testing throughput/latency - 1 topic, 1 consumerGroup, consumersPerSubscription = partitions
     *
     * There are a couple of options here:
     * - assume a relatively fixed number of partition
     * - assume pubSub = partitions
     *
     * The relatively fixed number seems to provide for greater throughput.
     */
    protected OMBWorkload createBasicWorkload(double targetThroughput, int pubSub, Profile profile) {
        LOGGER.info("Creating workload of {} throughput for {} pub/sub", String.format("%,.2f", targetThroughput), pubSub);
        OMBWorkload ombWorkload = new OMBWorkload()
                .setName("workload")
                .setTopics(1)
                .setPartitionsPerTopic(Math.max(pubSub, nominalPartitionCount))
                .setMessageSize(profile.messageSize)
                .setPayloadFile(profile.payloadFile)
                .setProducersPerTopic(pubSub)
                .setSubscriptionsPerTopic(1)
                .setConsumerPerSubscription(pubSub)
                .setProducerRate((int) (targetThroughput/profile.messageSize));

        ombWorkload.keyDistributor = KeyDistributorType.KEY_ROUND_ROBIN;

        //if (producerOnly) {
        //ombWorkload.setSubscriptionsPerTopic(0)
        //ombWorkload.setConsumerPerSubscription(0)
        //}
        return ombWorkload;
    }

    protected OMBWorkloadResult doTestRun(String name, OMBWorkload ombWorkload, Profile profile)
            throws Exception {
        //alternative: use a small, or even smallest number, of workers that will be needed
        //for lower producer tests this means relatively fewer consumers
        //e.g. noOfWorkers = 2 * Math.max(1, pubSub / 6);
        //this however leads to worse throughput

        // we've autosized the workers for the nodes, so just create 2 workers for each node
        int numberOfWorkers = 2*profilingResult.ombWorkerNodes;
        List<String> instanceWorkers = omb.deployWorkers(numberOfWorkers);
        assertEquals(numberOfWorkers, instanceWorkers.size(), String.format("failed to create %s omb workers", numberOfWorkers));

        File instanceDir = new File(logDir, name);
        assertTrue(instanceDir.mkdir() || instanceDir.exists(), String.format("failed to create directory %s", instanceDir.getName()));

        OMBDriver driver = createDriver(instanceBootstrap, profile);

        // run the workload
        OMBWorkloadResult result = omb.runWorkload(instanceDir, driver, instanceWorkers, ombWorkload);

        // store the filtered json data into a file
        TestUtils.createJsonObject(instanceDir, result.getTestResult());

        omb.deleteWorkers();

        // moved into the workload generator - TODO: looking here seems to see errors that do not show up during the run
        //if (result.getTestResult().aggregatedConsumerErrors > 0 || result.getTestResult().aggregatedPublishErrors > 0) {
        //    throw new IllegalStateException("There were errors during the test run, the logs should be examined.");
        //}

        return result;
    }

    /**
     *  create driver assuming a nominal latency optimized scenario
     *  see https://www.confluent.io/blog/configure-kafka-to-minimize-latency/ https://docs.confluent.io/cloud/current/client-apps/optimizing/latency.html
     */
    protected OMBDriver createDriver(String instanceBootstrap, Profile profile) {
        OMBDriver driver = new OMBDriver()
                .setReplicationFactor(replicationFactor)
                // insync replicas should not affect latency, so leave at a majority - which may need to change when the number of brokers does
                // don't retain past 5 minutes, that is beyond any retention we'll need for testing
                .setTopicConfig(profile.topicConfig)
                .setCommonConfigWithBootstrapUrl(instanceBootstrap)
                .setProducerConfig(profile.producerConfig)
                .setConsumerConfig(profile.consumerConfig);
        return driver;
    }

}
