package org.bf2.performance;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Quantity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

@Tag(TestTags.PERF)
public class ManagedKafkaThroughputTest extends TestBase {
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaThroughputTest.class);
    private static final Quantity WORKER_SIZE = Quantity.parse("6Gi");

    private ManagedKafkaProvisioner kafkaProvisioner;
    private List<String> workers;
    private OMB omb;


    @BeforeAll
    void beforeAll() throws Exception {
        kafkaProvisioner = ManagedKafkaProvisioner.create(KubeClusterResource.connectToKubeCluster(PerformanceEnvironment.KAFKA_KUBECONFIG));
        kafkaProvisioner.setup();
        kafkaProvisioner.install();
    }

    @AfterAll
    void afterAll() throws Exception {
        kafkaProvisioner.uninstall();
        kafkaProvisioner.teardown();
    }

    @BeforeEach
    void beforeEach() throws Exception {
        omb = new OMB(KubeClusterResource.connectToKubeCluster(PerformanceEnvironment.OMB_KUBECONFIG));
        omb.install(kafkaProvisioner.getTlsConfig());
        omb.setWorkerContainerMemory(WORKER_SIZE);
        omb.addToEnv(new EnvVar("HISTOGRAM_NUMBER_OF_SIGNIFICANT_VALUE_DIGITS", "0", null));
    }

    @AfterEach
    void afterEach() throws Exception {
        omb.deleteWorkers();
        omb.uninstall();
    }

    @ParameterizedTest(name = "testValueProdThroughput{0}_{1}")
    @CsvSource({
            "1, 1, 1, 1",
            "1, 3, 2, 2",
            "1, 6, 6, 6",
            "1, 9, 9, 9",
            "1, 15, 15, 15",
    })
    @Tag(TestTags.CI)
    void testValueProdThroughput(int topics, int partitions, int producers, int consumers,
            TestInfo info) throws Exception {

        int numWorkers = 2 * Math.max(1, partitions/3);

        String testKey = String.format("%s-%s-%s-%s-%s", topics, partitions, producers, consumers, numWorkers);
        ManagedKafkaCapacity capacity = kafkaProvisioner.defaultCapacity(41943040); // 40MB
        String bootstrapHosts = kafkaProvisioner.deployCluster("cluster1", capacity, AdopterProfile.VALUE_PROD).waitUntilReady();

        int messageSize = 1024;
        ensureClientClusterCapacityForWorkers(omb.getOmbCluster(), numWorkers, WORKER_SIZE);

        workers = omb.deployWorkers(numWorkers);

        OMBDriver driver = new OMBDriver()
            .setReplicationFactor(3)
            .setTopicConfig("min.insync.replicas=1\n")
            .setCommonConfigWithBootstrapUrl(bootstrapHosts)
            .setProducerConfig("acks=1\n")
            .setConsumerConfig("auto.offset.reset=earliest\nenable.auto.commit=false\n");

        int workerProducerRate = 1000000;
        int patitionsPerTopic = partitions / topics;
        // want minimum of 2 subscriptions per topic, should not be greater than
        // partitions
        int subscriptionPerTopic = Math.min(Math.max(consumers / (2 * topics), 1), partitions);
        int consumerPerSubscription = Math.min(consumers / subscriptionPerTopic, subscriptionPerTopic);

        LOGGER.info("subscriptionPerTopic:{} : consumerPerSubscription {}", subscriptionPerTopic, consumerPerSubscription);

        OMBWorkloadResult result = omb.runWorkload(
                instanceDir, driver, workers, new OMBWorkload()
                    .setName(testKey)
                    .setTopics(topics)
                    .setPartitionsPerTopic(patitionsPerTopic)
                    .setMessageSize(messageSize)
                    .setPayloadFile("src/test/resources/payload/payload-1Kb.data")
                    .setSubscriptionsPerTopic(subscriptionPerTopic)
                    .setConsumerPerSubscription(consumerPerSubscription)
                    .setProducersPerTopic(producers / topics)
                    .setProducerRate(workerProducerRate)
                    .setConsumerBacklogSizeGB(0)
        );
        LOGGER.info("{} : results {}", testKey, result.getResultFile());
    }
}
