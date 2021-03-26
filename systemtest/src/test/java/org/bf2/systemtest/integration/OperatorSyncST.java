package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
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
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);
        var kafkacli = kube.client().customResources(kafkaCrdContext, Kafka.class, KafkaList.class);

        //Create mk using api
        resourceManager.addResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());
        resourceManager.addResource(extensionContext, mk);

        HttpResponse<String> res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        resourceManager.waitResourceCondition(mk, m ->
                ManagedKafkaResourceType.hasConditionStatus(m, ManagedKafkaCondition.Type.Ready, ManagedKafkaCondition.Status.True));
        LOGGER.info("ManagedKafka {} created", mkAppName);

        //Get status and compare with CR status
        ManagedKafkaStatus apiStatus = Serialization.jsonMapper()
                .readValue(SyncApiClient.getManagedKafkaStatus(mkAppName, syncEndpoint).body(), ManagedKafkaStatus.class);
        ManagedKafka managedKafka = ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get();

        assertEquals(managedKafka.getStatus().getAdminServerURI(), apiStatus.getAdminServerURI());
        assertEquals(managedKafka.getStatus().getCapacity().getTotalMaxConnections(), apiStatus.getCapacity().getTotalMaxConnections());
        assertEquals(managedKafka.getStatus().getCapacity().getIngressEgressThroughputPerSec(), apiStatus.getCapacity().getIngressEgressThroughputPerSec());
        assertEquals(managedKafka.getStatus().getCapacity().getMaxDataRetentionPeriod(), apiStatus.getCapacity().getMaxDataRetentionPeriod());
        assertEquals(managedKafka.getStatus().getCapacity().getMaxPartitions(), apiStatus.getCapacity().getMaxPartitions());
        assertEquals(managedKafka.getStatus().getCapacity().getMaxDataRetentionSize(), apiStatus.getCapacity().getMaxDataRetentionSize());
        apiStatus.getConditions()
                .forEach(condition -> assertTrue(ManagedKafkaResourceType.hasConditionStatus(managedKafka,
                        ManagedKafkaCondition.Type.valueOf(condition.getType()),
                        ManagedKafkaCondition.Status.valueOf(condition.getStatus()))));

        //Get agent status
        ManagedKafkaAgentStatus agentStatus = Serialization.jsonMapper()
                .readValue(SyncApiClient.getManagedKafkaAgentStatus(syncEndpoint).body(), ManagedKafkaAgentStatus.class);
        assertEquals(1, agentStatus.getConditions().size());
        assertNotNull(agentStatus.getTotalCapacity());
        assertNotNull(agentStatus.getRemainingCapacity());
        assertNotNull(agentStatus.getResizeInfo());
        assertNotNull(agentStatus.getRequiredNodeSizes());

        //Check if managed kafka deployed all components
        assertNotNull(ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get());
        assertNotNull(kafkacli.inNamespace(mkAppName).withName(mkAppName).get());
        assertTrue(kube.client().pods().inNamespace(mkAppName).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals("Running", ManagedKafkaResourceType.getAdminApiPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());

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
