package org.bf2.systemtest.framework;

import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.test.k8s.KubeClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AssertUtils {

    public static void assertManagedKafka(ManagedKafka mk) {
        KubeClient kube = KubeClient.getInstance();
        var kafkacli = kube.client().customResources(Kafka.class, KafkaList.class);

        assertNotNull(ManagedKafkaResourceType.getOperation().inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get());
        assertNotNull(kafkacli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get());
        assertTrue(kube.client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals("Running", ManagedKafkaResourceType.getAdminApiPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        if (!kube.isGenericKubernetes()) {
            assertEquals(1, ManagedKafkaResourceType.getKafkaExporterPods(mk).size());
        }
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());
    }

    public static void assertSyncManagedKafkaStatus(ManagedKafka mk, ManagedKafkaStatus syncStatus) {
        assertEquals(mk.getStatus().getAdminServerURI(), syncStatus.getAdminServerURI());
        assertEquals(mk.getStatus().getCapacity().getTotalMaxConnections(), syncStatus.getCapacity().getTotalMaxConnections());
        assertEquals(mk.getStatus().getCapacity().getIngressEgressThroughputPerSec(), syncStatus.getCapacity().getIngressEgressThroughputPerSec());
        assertEquals(mk.getStatus().getCapacity().getMaxDataRetentionPeriod(), syncStatus.getCapacity().getMaxDataRetentionPeriod());
        assertEquals(mk.getStatus().getCapacity().getMaxPartitions(), syncStatus.getCapacity().getMaxPartitions());
        assertEquals(mk.getStatus().getCapacity().getMaxDataRetentionSize(), syncStatus.getCapacity().getMaxDataRetentionSize());
        syncStatus.getConditions()
                .forEach(condition -> assertTrue(ManagedKafkaResourceType.hasConditionStatus(mk,
                        ManagedKafkaCondition.Type.valueOf(condition.getType()),
                        ManagedKafkaCondition.Status.valueOf(condition.getStatus()))));
    }

    public static void assertSyncAgentStatus(ManagedKafkaAgentStatus agentStatus) {
        assertEquals(1, agentStatus.getConditions().size());
        assertNotNull(agentStatus.getTotalCapacity());
        assertNotNull(agentStatus.getRemainingCapacity());
        assertNotNull(agentStatus.getResizeInfo());
        assertNotNull(agentStatus.getRequiredNodeSizes());
    }
}
