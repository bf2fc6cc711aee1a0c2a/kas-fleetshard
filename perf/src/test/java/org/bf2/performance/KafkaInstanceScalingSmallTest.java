package org.bf2.performance;

import io.fabric8.kubernetes.api.model.Quantity;
import io.openmessaging.benchmark.TestResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacity;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.performance.framework.TestTags;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testcase 1: Producer throughput with a single small Kafka cluster (K2)
 */
@Tag(TestTags.PERF)
public class KafkaInstanceScalingSmallTest extends TestBase {
    private static final Logger LOGGER = LogManager.getLogger(KafkaInstanceScalingSmallTest.class);
    private static final Quantity WORKER_SIZE = Quantity.parse("2Gi");

    static KafkaProvisioner kafkaProvisioner;
    static KubeClusterResource kafkaCluster;
    static OMB omb;

    List<String> workers;

    @BeforeAll
    void beforeAll() throws Exception {
        kafkaCluster = KubeClusterResource.connectToKubeCluster(Environment.KAFKA_KUBECONFIG);
        kafkaProvisioner = KafkaProvisioner.create(kafkaCluster);
        kafkaProvisioner.setup();
        omb = new OMB(KubeClusterResource.connectToKubeCluster(Environment.OMB_KUBECONFIG));
        omb.install();
    }

    @AfterAll
    void afterAll() throws Exception {
        omb.uninstall();
        kafkaProvisioner.teardown();
    }

    @BeforeEach
    void beforeEach() throws Exception {
        omb.setWorkerContainerMemory(WORKER_SIZE);
        kafkaProvisioner.install();
    }

    @AfterEach
    void afterEach() throws Exception {
        kafkaProvisioner.uninstall();
        omb.deleteWorkers();
    }

    @ParameterizedTest(name = "tesConnections_m{0}_r{1}_p{2}_c{3}")
    @CsvSource({"100, 50, 8, 8, 4Gi, 750m",
                "100, 50, 1, 15, 4Gi, 750m",
                "100, 50, 15, 1, 4Gi, 750m",
    })
    void testConnections(int maxConnections, int connectionCreationRate, int numProducers, int numConsumers, String ombWorkerMem, String ombWorkerCpu, TestInfo info) throws Exception {
        int messageSize = 1024;
        int targetRate = 2_000;
        int workersPerInstance = 2;

        ManagedKafkaCapacity kafkaCapacity = KafkaConfigurations.defaultCapacity((long) targetRate * messageSize * 2);
        kafkaCapacity.setMaxConnectionAttemptsPerSec(connectionCreationRate);
        kafkaCapacity.setTotalMaxConnections(maxConnections);
        ManagedKafkaBuilder template = KafkaConfigurations.apply(kafkaCapacity, "cluster1");

        KafkaDeployment kafkaDeployment = kafkaProvisioner.deployCluster(template.build(), AdopterProfile.SMALL_VALUE_PROD);

        omb.setWorkerContainerMemory(Quantity.parse(ombWorkerMem));
        omb.setWorkerCpu(Quantity.parse(ombWorkerCpu));
        workers = omb.deployWorkers(workersPerInstance);

        Map<KafkaDeployment, List<String>> workerMapping = new HashMap<>();

        Iterator<String> workerIt = workers.iterator();
        Map<KafkaDeployment, String> instanceBootstrap = new HashMap<>();
        List<String> ws = new ArrayList<>();
        for (int w = 0; w < workersPerInstance; w++) {
            ws.add(workerIt.next());
        }
        workerMapping.put(kafkaDeployment, ws);
        instanceBootstrap.put(kafkaDeployment, kafkaDeployment.readyFuture().get());

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        AtomicInteger timeout = new AtomicInteger();
        List<TestResult> testResults = new ArrayList<>();
        try {
            List<Future<OMBWorkloadResult>> results = new ArrayList<>();
            for (Map.Entry<KafkaDeployment, String> entry : instanceBootstrap.entrySet()) {
                File ombDir = new File(instanceDir, entry.getKey().getManagedKafka().getMetadata().getName());
                Files.createDirectories(ombDir.toPath());

                OMBDriver driver = new OMBDriver()
                        .setReplicationFactor(3)
                        .setTopicConfig("min.insync.replicas=2\n")
                        .setCommonConfig(String.format("bootstrap.servers=%s\nsecurity.protocol=SSL\nssl.truststore.password=testing\nssl.truststore.type=JKS\nssl.truststore.location=/cert/ca.jks\n", entry.getValue()))
                        .setProducerConfig("acks=all\n")
                        .setConsumerConfig("auto.offset.reset=earliest\nenable.auto.commit=false\n");

                OMBWorkload workload = new OMBWorkload()
                        .setName(String.format("Kafka Cluster: %s", entry.getKey().getManagedKafka().getMetadata().getName()))
                        .setTopics(1)
                        .setPartitionsPerTopic(99)
                        .setMessageSize(messageSize)
                        .setPayloadFile("src/test/resources/payload/payload-1Kb.data")
                        .setSubscriptionsPerTopic(numConsumers)
                        .setConsumerPerSubscription(1)
                        .setProducersPerTopic(numProducers)
                        .setProducerRate(targetRate)
                        .setConsumerBacklogSizeGB(0);
                timeout.set(Math.max(workload.getTestDurationMinutes() + workload.getWarmupDurationMinutes(), timeout.get()));

                results.add(executorService.submit(() -> {
                    OMBWorkloadResult result = omb.runWorkload(ombDir, driver, workerMapping.get(entry.getKey()), workload);
                    LOGGER.info("Result stored in {}", result.getResultFile().getAbsolutePath());
                    return result;
                }));
            }
            for (Future<OMBWorkloadResult> result : results) {
                testResults.add(result.get(timeout.get() * 2L, TimeUnit.MINUTES).getTestResult());
            }
        } finally {
            executorService.shutdown();
            executorService.awaitTermination(1, TimeUnit.MINUTES);
        }

    }

    @Test
    void testValueProdCiCd(TestInfo info) throws Exception {
        int noOfWorkers = 2;
        int messageSize = 1024;
        int targetRate = 40_000;
        // Gather required info to spin up a kafka cluster and deploy kafka cluster.

        // create template to deploy the cluster
        ManagedKafkaCapacity kafkaCapacity = KafkaConfigurations.defaultCapacity((long) targetRate * messageSize);
        String clusterName = "cicdcluster";
        ManagedKafkaBuilder template = KafkaConfigurations.apply(kafkaCapacity, clusterName);

        ManagedKafka instance = template.build();
        KafkaDeployment kd = kafkaProvisioner.deployCluster(instance, AdopterProfile.VALUE_PROD);

        String instanceBootstrap = kd.waitUntilReady();

        // create omb workers
        workers = omb.deployWorkers(noOfWorkers);
        List<String> instanceWorkers = new ArrayList<>(workers);
        assertEquals(2, instanceWorkers.size(), String.format("failed to create %s omb workers", noOfWorkers));

        // create a directory for the test case.
        File instanceDir = new File(testDir, "testCiCd");
        assertTrue(instanceDir.mkdir(), String.format("failed to create directory %s", instanceDir.getName()));

        // create driver.
        File ombDir = new File(instanceDir, "testresults");
        Files.createDirectories(ombDir.toPath());
        OMBDriver driver = new OMBDriver()
                .setReplicationFactor(1)
                .setTopicConfig("min.insync.replicas=1\n")
                .setCommonConfig(String.format("bootstrap.servers=%s\nsecurity.protocol=SSL\nssl.truststore.password=testing\nssl.truststore.type=JKS\nssl.truststore.location=/cert/ca.jks\n", instanceBootstrap))
                .setProducerConfig("acks=all\n")
                .setConsumerConfig("auto.offset.rest=earliest\nenable.auto.commit=false\n");

        // construct the workload
        OMBWorkload ombWorkload = new OMBWorkload()
                .setName("CiCdPerfTest")
                .setTopics(1)
                .setPartitionsPerTopic(10)
                .setMessageSize(1024)
                .setPayloadFile("src/test/resources/payload/payload-1Kb.data")
                .setSubscriptionsPerTopic(1)
                .setConsumerPerSubscription(1)
                .setProducersPerTopic(1)
                .setProducerRate(4_000)
                .setConsumerBacklogSizeGB(0);

        // run the workload
        OMBWorkloadResult result = omb.runWorkload(ombDir, driver, instanceWorkers, ombWorkload);

        // store the filtered json data into a file
        TestUtils.createJsonObject(testDir, result.getTestResult());
    }
}
