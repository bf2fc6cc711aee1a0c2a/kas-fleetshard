package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.systemtest.api.SyncApiClient;
import org.bf2.systemtest.framework.AssertUtils;
import org.bf2.systemtest.framework.ParallelTest;
import org.bf2.systemtest.framework.SequentialTest;
import org.bf2.systemtest.framework.TimeoutBudget;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ManagedKafkaST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaST.class);
    private String syncEndpoint;
    private StrimziOperatorManager strimziOperatorManager = new StrimziOperatorManager();

    @BeforeAll
    void deploy() throws Exception {
        CompletableFuture.allOf(
                strimziOperatorManager.installStrimzi(kube),
                FleetShardOperatorManager.deployFleetShardOperator(kube),
                FleetShardOperatorManager.deployFleetShardSync(kube)).join();
        syncEndpoint = FleetShardOperatorManager.createEndpoint(kube);
        LOGGER.info("Endpoint address {}", syncEndpoint);
    }

    @AfterAll
    void clean() {
        CompletableFuture.allOf(
                FleetShardOperatorManager.deleteFleetShard(kube),
                strimziOperatorManager.uninstallStrimziClusterWideResources(kube)).join();
    }

    @ParallelTest
    void testCreateDeleteCreateSameManagedKafka(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-create-delete";

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);
        resourceManager.createResource(extensionContext, mk);

        AssertUtils.assertManagedKafka(mk);

        LOGGER.info("Remove managedKafka");
        resourceManager.deleteResource(mk);

        LOGGER.info("Create managedkafka again");
        //added more timeout because of strimzi reconcile interval
        resourceManager.createResource(extensionContext, TimeoutBudget.ofDuration(Duration.ofMinutes(15)), mk);

        AssertUtils.assertManagedKafka(mk);
    }

    @ParallelTest
    void testDeleteDeployedResources(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-resource-recovery";

        var kafkacli = kube.client().customResources(Kafka.class, KafkaList.class);

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);

        resourceManager.createResource(extensionContext, mk);

        AssertUtils.assertManagedKafka(mk);

        LOGGER.info("Delete resources in namespace {}", mkAppName);
        kube.client().apps().deployments().inNamespace(mkAppName).withLabel("app.kubernetes.io/managed-by", FleetShardOperatorManager.OPERATOR_NAME).delete();
        kafkacli.inNamespace(mkAppName).withLabel("app.kubernetes.io/managed-by", FleetShardOperatorManager.OPERATOR_NAME).delete();

        assertTrue(resourceManager.waitResourceCondition(mk, m ->
                ManagedKafkaResourceType.hasConditionStatus(m, ManagedKafkaCondition.Type.Ready, ManagedKafkaCondition.Status.False)));

        assertTrue(resourceManager.waitResourceCondition(mk, m ->
                        ManagedKafkaResourceType.hasConditionStatus(m, ManagedKafkaCondition.Type.Ready, ManagedKafkaCondition.Status.True),
                TimeoutBudget.ofDuration(Duration.ofMinutes(15))));

        AssertUtils.assertManagedKafka(mk);
    }

    @SequentialTest
    void testCreateManagedKafkaRestartKubeApi(ExtensionContext extensionContext) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            String mkAppName = "mk-test-restart-kubeapi";
            ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);

            //start restarting kubeapi
            executor.execute(TestUtils::restartKubeApi);
            Thread.sleep(5_000);

            //Create mk using api
            resourceManager.addResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());
            resourceManager.addResource(extensionContext, mk);

            HttpResponse<String> res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
            assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

            //stop restarting kubeapi
            executor.shutdownNow();

            assertTrue(resourceManager.waitResourceCondition(mk, m ->
                            ManagedKafkaResourceType.hasConditionStatus(m, ManagedKafkaCondition.Type.Ready, ManagedKafkaCondition.Status.True),
                    TimeoutBudget.ofDuration(Duration.ofMinutes(15))));
            LOGGER.info("ManagedKafka {} created", mkAppName);

            // wait for the sync to be up-to-date
            TestUtils.waitFor("Managed kafka status sync", 1_000, 60_000, () -> {
                try {
                    String statusBody = SyncApiClient.getManagedKafkaStatus(mk.getId(), syncEndpoint).body();
                    if (statusBody.isEmpty()) {
                        return false;
                    }
                    ManagedKafkaStatus apiStatus = Serialization.jsonMapper().readValue(statusBody, ManagedKafkaStatus.class);
                    return ManagedKafkaResourceType.hasConditionStatus(apiStatus, ManagedKafkaCondition.Type.Ready,
                            ManagedKafkaCondition.Status.True);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });

            //Get status and compare with CR status
            ManagedKafkaStatus apiStatus = Serialization.jsonMapper()
                    .readValue(SyncApiClient.getManagedKafkaStatus(mk.getId(), syncEndpoint).body(), ManagedKafkaStatus.class);
            ManagedKafka managedKafka = ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get();

            AssertUtils.assertManagedKafkaStatus(managedKafka, apiStatus);

            //Get agent status
            ManagedKafkaAgentStatus agentStatus = Serialization.jsonMapper()
                    .readValue(SyncApiClient.getManagedKafkaAgentStatus(syncEndpoint).body(), ManagedKafkaAgentStatus.class);

            AssertUtils.assertManagedKafkaAgentStatus(agentStatus);

            //Check if managed kafka deployed all components
            AssertUtils.assertManagedKafka(mk);

            //start restarting kubeapi
            executor = Executors.newFixedThreadPool(1);
            executor.execute(TestUtils::restartKubeApi);
            Thread.sleep(5_000);

            //delete mk using api
            res = SyncApiClient.deleteManagedKafka(mk.getId(), syncEndpoint);
            assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

            //stop restarting kubeapi
            executor.shutdownNow();

            ManagedKafkaResourceType.isDeleted(mk);

            LOGGER.info("ManagedKafka {} deleted", mkAppName);
        } finally {
            executor.shutdownNow();
        }
    }
}
