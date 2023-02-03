package org.bf2.performance;

import io.fabric8.kubernetes.api.model.Quantity;
import io.openmessaging.benchmark.TestResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.performance.framework.KubeClusterResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionCreationRateTest extends TestBase {
    private static final Logger LOGGER = LogManager.getLogger(ConnectionCreationRateTest.class);

    static ManagedKafkaProvisioner kafkaProvisioner;
    static KubeClusterResource kafkaCluster;
    static OMB omb;

    List<String> workers;

    @BeforeAll
    void beforeAll() throws Exception {
        omb = new OMB(KubeClusterResource.connectToKubeCluster(PerformanceEnvironment.OMB_KUBECONFIG));
    }

    @AfterAll
    void afterAll() throws Exception {
        omb.uninstall();
    }

    @AfterEach
    void afterEach() throws Exception {
        omb.deleteWorkers();
    }

    @ParameterizedTest(name = "testConnectionCreationRate: [{index}] {0}, {1}, {3}")
    @CsvFileSource(resources = "/test-inputs/ConnectionCreationRateTestInput.csv", useHeadersInDisplayName = true)
    void testConnectionCreationRate(int numProducers, int numConsumers, int replicationFactor, int numWorkers, String ombWorkerMem, String ombWorkerCpu, String bootstrapURL, String clientId, String clientSecret, String tokenEndpointURL, String trustStoreFileName, String trustStorePassword, TestInfo info) throws Exception {
        int messageSize = 1024;
        int targetRate = 100;
        String instanceName = bootstrapURL.split("\\.", 2)[0];

        omb.install(Base64.getEncoder().encodeToString(Files.readAllBytes(Paths.get(trustStoreFileName))));
        omb.setWorkerContainerMemory(Quantity.parse(ombWorkerMem));
        omb.setWorkerCpu(Quantity.parse(ombWorkerCpu));
        workers = omb.deployWorkers(numWorkers);

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        AtomicInteger timeout = new AtomicInteger();
        List<TestResult> testResults = new ArrayList<>();

        try {
            File ombDir = new File(instanceDir, instanceName);
            Files.createDirectories(ombDir.toPath());

            // OAuthBearer connection details
            String driverCommonConfig = new StringBuilder("sasl.mechanism=OAUTHBEARER")
                .append("\n")
                .append("security.protocol=SASL_SSL")
                .append("\n")
                .append("sasl.oauthbearer.token.endpoint.url=")
                .append(tokenEndpointURL)
                .append("\n")
                .append("ssl.truststore.location=/cert/listener.jks\n")
                .append("ssl.truststore.password=password\n")
                .append("sasl.jaas.config=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required scope=\"openid\" clientId=\"")
                .append(clientId)
                .append("\" ")
                .append("clientSecret=\"")
                .append(clientSecret)
                .append("\" ;")
                .append("\n")
                .append("sasl.login.callback.handler.class=org.apache.kafka.common.security.oauthbearer.secured.OAuthBearerLoginCallbackHandler")
                .append("\n")
                .append("bootstrap.servers=")
                .append(bootstrapURL)
                .append("\n")
                .toString();

            OMBDriver driver = new OMBDriver()
                .setReplicationFactor(replicationFactor)
                .setTopicConfig("")
                .setCommonConfig(driverCommonConfig)
                .setProducerConfig("acks=all\n")
                .setConsumerConfig("auto.offset.reset=earliest\nenable.auto.commit=false\n");

            OMBWorkload workload = new OMBWorkload()
                .setName(String.format("Kafka Cluster: %s", instanceName))
                .setTopics(1)
                .setPartitionsPerTopic(1)
                .setWarmupDurationMinutes(0)
                .setTestDurationMinutes(1)
                .setMessageSize(messageSize)
                .setPayloadFile("src/test/resources/payload/payload-1Kb.data")
                .setSubscriptionsPerTopic(numConsumers)
                .setConsumerPerSubscription(1)
                .setProducersPerTopic(numProducers)
                .setProducerRate(targetRate)
                .setConsumerBacklogSizeGB(0);
            timeout.set(Math.max(workload.getTestDurationMinutes() + workload.getWarmupDurationMinutes(), timeout.get()) * 10);

            Future<OMBWorkloadResult> resultFuture = executorService.submit(() -> {
                    OMBWorkloadResult result = omb.runWorkload(ombDir, driver, workers, workload);
                    LOGGER.info("Result stored in {}", result.getResultFile().getAbsolutePath());
                    return result;
                });
            testResults.add(resultFuture.get(timeout.get() * 5L, TimeUnit.MINUTES).getTestResult());
        } finally {
            executorService.shutdown();
            executorService.awaitTermination(1, TimeUnit.MINUTES);
        }

    }
}
