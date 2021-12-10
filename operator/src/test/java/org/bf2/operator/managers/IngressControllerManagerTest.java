package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.api.model.RouteTargetReferenceBuilder;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.OperandUtils;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaRoute;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class IngressControllerManagerTest {

    @Inject
    IngressControllerManager ingressControllerManager;

    @Inject
    OpenShiftClient openShiftClient;

    @KubernetesTestServer
    KubernetesServer kubernetesServer;

    @Test
    public void testIngressControllerCreationWithNoZones() {
        ingressControllerManager.reconcileIngressControllers();

        List<IngressController> ingressControllers = openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).list().getItems();
        assertEquals(1, ingressControllers.size(), "Expected only one IngressController");
        assertEquals("kas", ingressControllers.get(0).getMetadata().getName(), "Expected the IngressController to be named kas");
        assertEquals(0, ingressControllers.get(0).getSpec().getReplicas(), "Expected 0 replicas because there are 0 nodes");
    }

    @Test
    public void testIngressControllerCreationWith3Zones() {

        IntStream.range(0, 3).forEach(i -> {
            Node node = new NodeBuilder()
                    .editOrNewMetadata()
                        .withName("z"+i)
                        .withLabels(Map.of(IngressControllerManager.WORKER_NODE_LABEL, "", IngressControllerManager.TOPOLOGY_KEY, "zone"+i))
                    .endMetadata()
                    .build();
            openShiftClient.nodes().create(node);
        });

        ingressControllerManager.reconcileIngressControllers();

        List<IngressController> ingressControllers = openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).list().getItems();
        assertEquals(4, ingressControllers.size(), "Expected 4 IngressControllers: one per zone, and one multi-zone");

        // make sure the zone specific has node placements
        assertTrue(ingressControllers.stream().allMatch(c -> {
            if (c.getMetadata().getName().equals("kas")) {
                return true;
            }
            return c.getSpec().getNodePlacement() != null;
        }));
    }

    @Test
    public void testIngressControllerReplicaCounts() {
        List<Node> nodes = buildNodes(9);

        assertEquals(2, ingressControllerManager.numReplicasForAllZones(nodes));
        assertEquals(2, ingressControllerManager.numReplicasForZone("zone0", nodes));

        nodes = buildNodes(210);

        assertEquals(3, ingressControllerManager.numReplicasForAllZones(nodes));
        assertEquals(3, ingressControllerManager.numReplicasForZone("zone0", nodes));

        nodes = buildNodes(303);

        assertEquals(5, ingressControllerManager.numReplicasForAllZones(nodes));
        assertEquals(5, ingressControllerManager.numReplicasForZone("zone0", nodes));
    }

    private List<Node> buildNodes(int nodeCount) {
        List<Node> nodes = IntStream.range(0, nodeCount).mapToObj(i ->
            new NodeBuilder()
                    .editOrNewMetadata()
                        .withName("z"+i)
                        .withLabels(Map.of(IngressControllerManager.WORKER_NODE_LABEL, "", IngressControllerManager.TOPOLOGY_KEY, "zone"+(i%3)))
                    .endMetadata()
                    .build()
        ).collect(Collectors.toList());
        return nodes;
    }

    @Test
    public void testGetManagedKafkaRoutesFor() {
        final String mkName = "my-managedkafka";
        ManagedKafka mk = new ManagedKafkaBuilder()
                .withNewMetadata().withName(mkName).withNamespace(mkName).endMetadata()
                .withSpec(new ManagedKafkaSpecBuilder()
                        .withNewEndpoint()
                        .withBootstrapServerHost("bs.bf2.example.tld")
                        .endEndpoint()
                        .build())
                .build();

        final Function<? super String, ? extends Route> makeRoute = broker -> new RouteBuilder()
                .editOrNewMetadata()
                .withName(mkName + "-" + broker.replace("broker", "kafka"))
                .withNamespace(mkName)
                .addNewOwnerReference().withApiVersion(Kafka.V1BETA2).withKind(Kafka.RESOURCE_KIND).withName(AbstractKafkaCluster.kafkaClusterName(mk)).endOwnerReference()
                .endMetadata()
                .editOrNewSpec()
                .withHost(broker + "-bs.bf2.example.tld")
                .withTo(new RouteTargetReferenceBuilder().withKind("Service")
                        .withName(mkName + "-" + broker)
                        .withWeight(100)
                        .build())
                .endSpec()
                .build();

        final Function<? super String, ? extends Service> suffixToService = suffix -> new ServiceBuilder()
                .editOrNewMetadata()
                .withName(mkName + "-" + suffix)
                .withNamespace(mkName)
                .endMetadata()
                .editOrNewSpec()
                .withSelector(Map.of("dummy-label", mkName + "-" + suffix))
                .endSpec()
                .build();

        final Function<? super String, ? extends Pod> suffixToPod = suffix -> new PodBuilder()
                .editOrNewMetadata()
                .withName(mkName + "-" + suffix)
                .withNamespace(mkName)
                .addToLabels(Map.of("dummy-label", mkName + "-" + suffix, "app.kubernetes.io/name", "kafka",
                        OperandUtils.MANAGED_BY_LABEL, OperandUtils.STRIMZI_OPERATOR_NAME))
                .endMetadata()
                .editOrNewSpec()
                .withNodeName("zone" + "-" + suffix)
                .endSpec()
                .build();

        final Function<? super String, ? extends Node> suffixToNode = suffix -> new NodeBuilder()
                .editOrNewMetadata()
                .withName("zone" + "-" + suffix)
                .withLabels(Map.of(IngressControllerManager.TOPOLOGY_KEY, "zone" + "-" + suffix,
                        IngressControllerManager.WORKER_NODE_LABEL, "true"))
                .endMetadata()
                .build();

        List<String> suffixes = List.of("broker-0", "broker-1", "broker-2");

        suffixes.stream().map(makeRoute).forEach(route -> openShiftClient.routes().inNamespace(mkName).createOrReplace(route));
        suffixes.stream().map(suffixToService).forEach(svc -> openShiftClient.services().inNamespace(mkName).createOrReplace(svc));
        suffixes.stream().map(suffixToPod).forEach(pod -> openShiftClient.pods().inNamespace(mkName).createOrReplace(pod));
        suffixes.stream().map(suffixToNode).forEach(node -> openShiftClient.nodes().createOrReplace(node));

        ingressControllerManager.reconcileIngressControllers();
        List<ManagedKafkaRoute> managedKafkaRoutes = ingressControllerManager.getManagedKafkaRoutesFor(mk);

        assertEquals(5, managedKafkaRoutes.size());

        assertEquals(
                managedKafkaRoutes.stream().sorted(Comparator.comparing(ManagedKafkaRoute::getName)).collect(Collectors.toList()),
                managedKafkaRoutes,
                "Expected list of ManagedKafkaRoutes to be sorted by name");

        assertEquals("admin-server", managedKafkaRoutes.get(0).getName());
        assertEquals("admin-server", managedKafkaRoutes.get(0).getPrefix());
        assertEquals("ingresscontroller.kas.testing.domain.tld", managedKafkaRoutes.get(0).getRouter());

        assertEquals("bootstrap", managedKafkaRoutes.get(1).getName());
        assertEquals("", managedKafkaRoutes.get(1).getPrefix());
        assertEquals("ingresscontroller.kas.testing.domain.tld", managedKafkaRoutes.get(1).getRouter());

        assertEquals("broker-0", managedKafkaRoutes.get(2).getName());
        assertEquals("broker-0", managedKafkaRoutes.get(2).getPrefix());
        assertEquals("ingresscontroller.kas-zone-broker-0.testing.domain.tld", managedKafkaRoutes.get(2).getRouter());

        assertEquals("broker-1", managedKafkaRoutes.get(3).getName());
        assertEquals("broker-1", managedKafkaRoutes.get(3).getPrefix());
        assertEquals("ingresscontroller.kas-zone-broker-1.testing.domain.tld", managedKafkaRoutes.get(3).getRouter());

        assertEquals("broker-2", managedKafkaRoutes.get(4).getName());
        assertEquals("broker-2", managedKafkaRoutes.get(4).getPrefix());
        assertEquals("ingresscontroller.kas-zone-broker-2.testing.domain.tld", managedKafkaRoutes.get(4).getRouter());
    }

    @AfterEach
    void cleanup() {
        // clears the mock server state
        // won't be needed after quarkus fixes issues with WithKubernetesTestServer
        kubernetesServer.getMockServer().setDispatcher(new KubernetesCrudDispatcher());
    }
}
