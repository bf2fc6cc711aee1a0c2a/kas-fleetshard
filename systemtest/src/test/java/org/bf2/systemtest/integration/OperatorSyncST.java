package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.systemtest.api.SyncApiClient;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OperatorSyncST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(OperatorSyncST.class);
    private String syncEndpoint;

    @BeforeAll
    void deployStrimzi() throws Exception {
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
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);
        var kafkacli = kube.client().customResources(kafkaCrdContext, Kafka.class, KafkaList.class);

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        //Create mk using api
        resourceManager.addAlreadyCreatedResources(extensionContext, mk);
        HttpResponse<String> res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        resourceManager.waitResourceCondition(mk, m ->
                "True".equals(ManagedKafkaResourceType.getConditionStatus(m, ManagedKafkaCondition.Type.Ready)));

        LOGGER.info("ManagedKafka {} created", mkAppName);
        assertNotNull(ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get());
        assertNotNull(kafkacli.inNamespace(mkAppName).withName(mkAppName).get());
        assertTrue(kube.client().pods().inNamespace(mkAppName).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals("Running", ManagedKafkaResourceType.getAdminApiPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());

        //get created mk using api
        res = SyncApiClient.getKafkas(syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_OK, res.statusCode());
        List<ManagedKafka> kafkas = SyncApiClient.getKafkasFromResponse(res).getItems();
        assertEquals(1, kafkas.size());
        assertEquals(mk.getId(), kafkas.get(0).getId());
        assertEquals(mk.getMetadata().getName(), kafkas.get(0).getMetadata().getName());

        //delete mk using api
        res = SyncApiClient.deleteManagedKafka(mkAppName, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        TestUtils.waitFor("Managed kafka is removed", 1_000, 300_000, () -> {
            ManagedKafka m = ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get();
            List<Pod> pods = kube.client().pods().inNamespace(mkAppName).list().getItems();
            return m == null && pods.size() == 0;
        });

        LOGGER.info("ManagedKafka {} deleted", mkAppName);
    }
}
