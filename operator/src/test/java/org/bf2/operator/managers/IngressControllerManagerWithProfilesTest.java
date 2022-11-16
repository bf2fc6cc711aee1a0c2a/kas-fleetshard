package org.bf2.operator.managers;

import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ProfileBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.List;
import java.util.LongSummaryStatistics;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class IngressControllerManagerWithProfilesTest {

    @Inject
    IngressControllerManager ingressControllerManager;

    @Inject
    OpenShiftClient openShiftClient;

    @KubernetesTestServer
    KubernetesServer kubernetesServer;

    @InjectMock
    InformerManager informerManager;

    @Test
    void testIngressControllerNodePlacement() {
        useProfileLabels();

        ingressControllerManager.reconcileIngressControllers();

        List<IngressController> ingressControllers = openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).list().getItems();
        String tolerations = Serialization.asYaml(ingressControllers.get(0).getSpec().getNodePlacement().getTolerations());

        // the additional toleration will cause the deployment to roll
        assertEquals("---\n"
                + "- effect: \"NoSchedule\"\n"
                + "  key: \"kas-fleetshard-ingress\"\n"
                + "  operator: \"Exists\"\n", tolerations);
    }

    @Test
    void testIngressControllerReplicaCountsWithoutCollocation() {
        useProfileLabels();

        // should only be 2 replicas for 60 standard instances
        long ingress = 50000000;
        assertEquals(2, ingressControllerManager.numReplicasForZone(new LongSummaryStatistics(1, 0, ingress, ingress*60), new LongSummaryStatistics(1, 0, ingress*2, ingress*120), 0, IngressControllerManagerTest.ZONE_PERCENTAGE));
    }

    void useProfileLabels() {
        // enable the profile
        Mockito.when(informerManager.getLocalAgent()).thenReturn(new ManagedKafkaAgentBuilder()
                .withNewMetadata()
                .withName(ManagedKafkaAgentResourceClient.RESOURCE_NAME)
                .endMetadata()
                .withNewSpec()
                .addToCapacity("standard", new ProfileBuilder().withMaxNodes(6).build())
                .endSpec()
                .build());
    }

}
