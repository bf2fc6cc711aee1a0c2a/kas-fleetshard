package org.bf2.performance;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Quantity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.operands.KafkaInstanceConfiguration;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacity;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.performance.framework.TestTags;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

// Testcase X: Discovers the minimum container memory and vCPU for the Managed Kafka adopters profiles
@Tag(TestTags.PERF)
public class ManagedKafkaValueProdMinimumTest extends TestBase {
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaValueProdMinimumTest.class);
    private static final Quantity WORKER_SIZE = Quantity.parse("6Gi");

    private ManagedKafkaProvisioner kafkaProvisioner;
    private List<String> workers;
    private OMB omb;

    @BeforeAll
    void beforeAll() throws Exception {
        kafkaProvisioner = ManagedKafkaProvisioner.create(KubeClusterResource.connectToKubeCluster(PerformanceEnvironment.KAFKA_KUBECONFIG));
        kafkaProvisioner.setup();
        omb = new OMB(KubeClusterResource.connectToKubeCluster(PerformanceEnvironment.OMB_KUBECONFIG));
        omb.install(kafkaProvisioner.getTlsConfig());
        omb.setWorkerContainerMemory(WORKER_SIZE);
        omb.addToEnv(new EnvVar("HISTOGRAM_NUMBER_OF_SIGNIFICANT_VALUE_DIGITS", "0", null));
    }

    @AfterAll
    void afterAll() throws Exception {
        omb.uninstall();
        kafkaProvisioner.teardown();
    }

    @BeforeEach
    void beforeEach() throws Exception {
        kafkaProvisioner.install();
    }

    @AfterEach
    void afterEach() throws Exception {
        kafkaProvisioner.uninstall();
        omb.deleteWorkers();
    }

    @ParameterizedTest(name = "testValueProdMinimumKafkaMem_{3}_{4}")
    @CsvSource({
            "41943040, 60000, 500, 12Gi, 2Gi",
            "41943040, 60000, 500, 12Gi, 3Gi",
            "41943040, 60000, 500, 12Gi, 4Gi",
            "41943040, 60000, 500, 12Gi, 5Gi",

            "41943040, 60000, 500, 10Gi, 2Gi",
            "41943040, 60000, 500, 10Gi, 3Gi",
            "41943040, 60000, 500, 10Gi, 4Gi",
            "41943040, 60000, 500, 10Gi, 5Gi",

            "41943040, 60000, 500, 8Gi, 2Gi",
            "41943040, 60000, 500, 8Gi, 3Gi",
            "41943040, 60000, 500, 8Gi, 4Gi",
            "41943040, 60000, 500, 8Gi, 5Gi",

            "41943040, 60000, 500, 6Gi, 2Gi",
            "41943040, 60000, 500, 6Gi, 3Gi",
    })
    @Tag(TestTags.CI)
    void testValueProdMinimumKafkaMem(long throughput, int workerProducerRate,
                                      int clients, String kafkaContainerMemory, String kafkaJavaMemory, TestInfo info) throws Exception {
        ManagedKafkaCapacity capacity = kafkaProvisioner.defaultCapacity(throughput);
        doTestValueProdMinimum(capacity, workerProducerRate, clients,
                "4Gi", "2Gi", kafkaContainerMemory, kafkaJavaMemory, "2000m", 10, 205,
                String.format("kf%s-%s", kafkaContainerMemory, kafkaJavaMemory), info.getDisplayName());
    }

    private void doTestValueProdMinimum(ManagedKafkaCapacity capacity, int workerProducerRate, int numClients,
                                        String zkContainerMemory, String zkJavaMemory, String kafkaContainerMemory, String kafkaJavaMemory,
                                        String kfCpu, int topics, int partitionsPerTopic, String key, String testName) throws Exception {
        int numWorkers = numClients / 10;
        int messageSize = 1024;
        ensureClientClusterCapacityForWorkers(omb.getOmbCluster(), numWorkers, WORKER_SIZE);

        workers = omb.deployWorkers(numWorkers);

        LOGGER.info("Test config: {}", key);
        KafkaInstanceConfiguration profile = AdopterProfile.buildProfile(
                zkContainerMemory, zkJavaMemory, "1000", kafkaContainerMemory, kafkaJavaMemory, kfCpu
        );

        String bootstrapHosts = kafkaProvisioner.deployCluster("cluster1", capacity, profile).waitUntilReady();

        OMBDriver driver = new OMBDriver()
                .setReplicationFactor(3)
                .setTopicConfig("min.insync.replicas=2\n")
                .setCommonConfigWithBootstrapUrl(bootstrapHosts)
                .setProducerConfig("acks=all\n")
                .setConsumerConfig("auto.offset.reset=earliest\nenable.auto.commit=false\n");

        int producerConsumer = numClients / topics / 2;
        OMBWorkloadResult result = omb.runWorkload(instanceDir, driver, workers, new OMBWorkload()
                .setName(key)
                .setTopics(topics)
                .setPartitionsPerTopic(partitionsPerTopic)
                .setMessageSize(messageSize)
                .setPayloadFile("src/test/resources/payload/payload-1Kb.data")
                .setSubscriptionsPerTopic(1)
                .setConsumerPerSubscription(producerConsumer)
                .setProducersPerTopic(producerConsumer)
                .setProducerRate(workerProducerRate)
                .setConsumerBacklogSizeGB(0));
        LOGGER.info("{} : results {}", key, result.getResultFile());

//        double threshold = 0.9 * targetRate;
//        List<Double> lowProduceRates = result.getTestResult().publishRate.stream().filter(rate -> rate < threshold).collect(Collectors.toList());
//        List<Double> lowConsumeRates = result.getTestResult().consumeRate.stream().filter(rate -> rate < threshold).collect(Collectors.toList());
//        LOGGER.info("{}: low produce : {} low consume: {}", key, lowProduceRates, lowConsumeRates);

//        assertTrue(lowProduceRates.isEmpty(), "Unexpectedly low produce rate(s): " + lowProduceRates);
//        assertTrue(lowConsumeRates.isEmpty(), "Unexpectedly low consume rate(s): " + lowConsumeRates);
    }

}
