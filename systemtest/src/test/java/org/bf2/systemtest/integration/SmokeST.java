package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.systemtest.api.SyncApiClient;
import org.bf2.systemtest.framework.AssertUtils;
import org.bf2.systemtest.framework.ParallelTest;
import org.bf2.systemtest.framework.TestTags;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SmokeST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(SmokeST.class);
    private String syncEndpoint;

    @BeforeAll
    void deploy() throws Exception {
        CompletableFuture.allOf(
                StrimziOperatorManager.installStrimzi(kube),
                FleetShardOperatorManager.deployFleetShardOperator(kube),
                FleetShardOperatorManager.deployFleetShardSync(kube)).join();
        syncEndpoint = FleetShardOperatorManager.createEndpoint(kube);
        LOGGER.info("Endpoint address {}", syncEndpoint);
    }

    @AfterAll
    void clean() throws InterruptedException {
        FleetShardOperatorManager.deleteFleetShard(kube);
        StrimziOperatorManager.uninstallStrimziClusterWideResources(kube);
    }

    @Tag(TestTags.SMOKE)
    @ParallelTest
    void testCreateManagedKafkaByOperator(ExtensionContext extensionContext) {
        String mkAppName = "mk-test-create";

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);

        resourceManager.createResource(extensionContext, mk);

        AssertUtils.assertManagedKafka(mk);
    }

    @Tag(TestTags.SMOKE)
    @ParallelTest
    void testCreateManagedKafkaBySync(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-deploy-api";
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);

        //Create mk using api
        resourceManager.addResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());
        resourceManager.addResource(extensionContext, mk);

        HttpResponse<String> res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        assertTrue(resourceManager.waitResourceCondition(mk, m ->
                ManagedKafkaResourceType.hasConditionStatus(m, ManagedKafkaCondition.Type.Ready, ManagedKafkaCondition.Status.True)));
        LOGGER.info("ManagedKafka {} created", mkAppName);

        // wait for the sync to be up-to-date
        TestUtils.waitFor("Managed kafka status sync", 1_000, 30_000, () -> {
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

        //delete mk using api
        res = SyncApiClient.deleteManagedKafka(mk.getId(), syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        ManagedKafkaResourceType.isDeleted(mk);

        LOGGER.info("ManagedKafka {} deleted", mkAppName);
    }
}
