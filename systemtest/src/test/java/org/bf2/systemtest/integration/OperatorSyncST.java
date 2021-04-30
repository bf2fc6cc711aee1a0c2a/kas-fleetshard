package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.systemtest.api.SyncApiClient;
import org.bf2.systemtest.framework.AssertUtils;
import org.bf2.systemtest.framework.TestTags;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OperatorSyncST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(OperatorSyncST.class);
    private String syncEndpoint;

    @BeforeAll
    void deploy() throws Exception {
        StrimziOperatorManager.installStrimzi(kube);
        FleetShardOperatorManager.deployFleetShardOperator(kube);
        FleetShardOperatorManager.deployFleetShardSync(kube);
        syncEndpoint = FleetShardOperatorManager.createEndpoint(kube);
        LOGGER.info("Endpoint address {}", syncEndpoint);
    }

    @AfterAll
    void clean() throws InterruptedException {
        FleetShardOperatorManager.deleteFleetShard(kube);
        StrimziOperatorManager.uninstallStrimziClusterWideResources(kube);
    }

    @Tag(TestTags.SMOKE)
    @Test
    void testCreateManagedKafkaUsingSync(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-deploy-api";
        doTestUsingSync(extensionContext, mkAppName, false);
    }

    @Test
    void testRestartKubeApi(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-restart-kubeapi";
        doTestUsingSync(extensionContext, mkAppName, true);
    }

    private void doTestUsingSync(ExtensionContext extensionContext, String mkAppName, boolean restartApi)
            throws Exception {
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);

        //Create mk using api
        resourceManager.addResource(extensionContext,
                new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());
        resourceManager.addResource(extensionContext, mk);

        if (restartApi) {
            restartKubeApi();
        }

        HttpResponse<String> res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        assertTrue(resourceManager.waitResourceCondition(mk, m -> ManagedKafkaResourceType.hasConditionStatus(m,
                ManagedKafkaCondition.Type.Ready, ManagedKafkaCondition.Status.True)));
        LOGGER.info("ManagedKafka {} created", mkAppName);

        if (restartApi) {
            restartKubeApi();
        }

        // wait for the sync to be up-to-date
        TestUtils.waitFor("Managed kafka status sync", 1_000, 30_000, () -> {
            try {
                String statusBody = SyncApiClient.getManagedKafkaStatus(mk.getId(), syncEndpoint).body();
                if (statusBody.isEmpty()) {
                    return false;
                }
                ManagedKafkaStatus apiStatus =
                        Serialization.jsonMapper().readValue(statusBody, ManagedKafkaStatus.class);
                return ManagedKafkaResourceType.hasConditionStatus(apiStatus, ManagedKafkaCondition.Type.Ready,
                        ManagedKafkaCondition.Status.True);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        //Get status and compare with CR status
        ManagedKafkaStatus apiStatus = Serialization.jsonMapper()
                .readValue(SyncApiClient.getManagedKafkaStatus(mk.getId(), syncEndpoint).body(),
                        ManagedKafkaStatus.class);
        ManagedKafka managedKafka =
                ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get();

        AssertUtils.assertManagedKafkaStatus(managedKafka, apiStatus);

        if (restartApi) {
            restartKubeApi();
        }

        //Get agent status
        ManagedKafkaAgentStatus agentStatus = Serialization.jsonMapper()
                .readValue(SyncApiClient.getManagedKafkaAgentStatus(syncEndpoint).body(),
                        ManagedKafkaAgentStatus.class);

        AssertUtils.assertManagedKafkaAgentStatus(agentStatus);

        //Check if managed kafka deployed all components
        AssertUtils.assertManagedKafka(mk);

        if (restartApi) {
            restartKubeApi();
        }

        //delete mk using api
        res = SyncApiClient.deleteManagedKafka(mk.getId(), syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        if (restartApi) {
            restartKubeApi();
        }

        TestUtils.waitFor("Managed kafka is removed", 1_000, 300_000, () -> {
            ManagedKafka m = ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get();
            List<Pod> pods = kube.client().pods().inNamespace(mkAppName).list().getItems();
            return m == null && pods.size() == 0;
        });

        LOGGER.info("ManagedKafka {} deleted", mkAppName);
    }

    private void restartKubeApi() throws InterruptedException {
        final String apiNamespace = kube.isGenericKubernetes() ? "kube-system" : "openshift-kube-apiserver";

        LOGGER.info("Restarting kubeapi");
        for (int i = 0; i < 60; i++) {
            if (!kube.isGenericKubernetes()) {
                kube.client().pods().inNamespace("openshift-apiserver").delete();
            }
            kube.client()
                    .pods()
                    .inNamespace(apiNamespace)
                    .list()
                    .getItems()
                    .stream()
                    .filter(pod -> pod.getMetadata().getName().contains("kube-apiserver-"))
                    .forEach(pod -> kube.client()
                            .pods()
                            .inNamespace(apiNamespace)
                            .withName(pod.getMetadata().getName())
                            .withGracePeriod(1000)
                            .delete());
            Thread.sleep(500);
        }
    }

}
