package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.api.model.RouteTargetReferenceBuilder;
import io.fabric8.openshift.api.model.operator.v1.Config;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.OperandUtils;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.operands.KafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaRoute;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class IngressControllerManagerTest {

    private static final double ZONE_PERCENTAGE = 1d/3;

    @Inject
    IngressControllerManager ingressControllerManager;

    @Inject
    OpenShiftClient openShiftClient;

    @KubernetesTestServer
    KubernetesServer kubernetesServer;

    @Inject
    KafkaCluster kafkaCluster;

    @Inject
    InformerManager informerManager;

    @Test
    public void testIngressControllerCreationWithNoZones() {
        QuarkusMock.installMockForType(Mockito.mock(InformerManager.class), InformerManager.class);

        ingressControllerManager.reconcileIngressControllers();

        List<IngressController> ingressControllers = openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).list().getItems();
        assertEquals(1, ingressControllers.size(), "Expected only one IngressController");
        assertEquals("kas", ingressControllers.get(0).getMetadata().getName(), "Expected the IngressController to be named kas");
        assertEquals(0, ingressControllers.get(0).getSpec().getReplicas(), "Expected 0 replicas because there are 0 nodes");

        // check the patch logic
        IngressController ic = ingressControllers.get(0);
        assertFalse(ingressControllerManager.shouldPatchIngressController(ic, ic));
        assertTrue(ingressControllerManager.shouldPatchIngressController(ic,
                new IngressControllerBuilder(ic).editMetadata()
                        .withLabels(Collections.emptyMap())
                        .endMetadata()
                        .build()));
    }

    @Test
    public void testReplicaReduction() {
        openShiftClient.resourceList((List)buildNodes(12)).createOrReplace();

        IntStream.range(0, 6).forEach(i -> {
            ManagedKafka mk = ManagedKafka.getDummyInstance(1);
            mk.getMetadata().setName("ingressTest" + i);
            mk.getMetadata().setNamespace("ingressTest");
            mk.getSpec().getCapacity().setIngressPerSec(Quantity.parse("300Mi"));
            mk.getSpec().getCapacity().setEgressPerSec(Quantity.parse("300Mi"));
            Kafka kafka = this.kafkaCluster.kafkaFrom(mk, null);
            openShiftClient.resource(kafka).createOrReplace();
        });
        informerManager.createKafkaInformer();

        ingressControllerManager.reconcileIngressControllers();
        // this is more than the number of nodes, but we're presuming node scaling is available
        checkAzReplicaCount(5);

        // remove two kafkas - we should keep the same number of replicas
        var kafkas = openShiftClient.resources(Kafka.class).inNamespace("ingressTest");
        assertTrue(kafkas.withName("ingressTest0").delete());
        assertTrue(kafkas.withName("ingressTest1").delete());
        ingressControllerManager.reconcileIngressControllers();
        checkAzReplicaCount(5);

        // remove two more kafkas - and we should reduce
        assertTrue(kafkas.withName("ingressTest2").delete());
        assertTrue(kafkas.withName("ingressTest3").delete());
        ingressControllerManager.reconcileIngressControllers();
        checkAzReplicaCount(2);
    }

    @Test
    void testReplicaReduction3to2() {
        openShiftClient.resourceList((List)buildNodes(12)).createOrReplace();

        IntStream.range(0, 3).forEach(i -> {
            ManagedKafka mk = ManagedKafka.getDummyInstance(1);
            mk.getMetadata().setName("ingressTest" + i);
            mk.getMetadata().setNamespace("ingressTest");
            mk.getSpec().getCapacity().setIngressPerSec(Quantity.parse("300Mi"));
            mk.getSpec().getCapacity().setEgressPerSec(Quantity.parse("300Mi"));
            Kafka kafka = this.kafkaCluster.kafkaFrom(mk, null);
            openShiftClient.resource(kafka).createOrReplace();
        });
        informerManager.createKafkaInformer();

        ingressControllerManager.reconcileIngressControllers();
        checkAzReplicaCount(3);

        // remove two kafkas - we should reduce to 2
        var kafkas = openShiftClient.resources(Kafka.class).inNamespace("ingressTest");
        assertTrue(kafkas.withName("ingressTest0").delete());
        assertTrue(kafkas.withName("ingressTest1").delete());
        ingressControllerManager.reconcileIngressControllers();
        checkAzReplicaCount(2);
    }

    private void checkAzReplicaCount(int count) {
        List<IngressController> ingressControllers = openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).list().getItems();
        IngressController ic = ingressControllers.stream().filter(c -> !c.getMetadata().getName().equals("kas")).findFirst().get();
        assertEquals(count, ic.getSpec().getReplicas());
    }

    @Test
    public void testIngressControllerCreationWith3Zones() {
        buildNodes(3).stream().forEach(n -> openShiftClient.nodes().create(n));

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
    public void testIngressControllerCreationWithMultiUnitInstances() {
        buildNodes(99).stream().forEach(n -> openShiftClient.nodes().create(n));

        for (int i = 0; i < 25; i++) {
            int size = (i%5) + 1;
            ManagedKafka mk = ManagedKafka.getDummyInstance(i);
            mk.getSpec().getCapacity().setEgressPerSec(Quantity.parse(100*size+"Mi"));
            mk.getSpec().getCapacity().setIngressPerSec(Quantity.parse(50*size+"Mi"));
            mk.getSpec().getCapacity().setTotalMaxConnections(3000*size);
            mk.getSpec().getCapacity().setMaxPartitions(1500*size);
            Kafka kafka = this.kafkaCluster.kafkaFrom(mk, null);
            openShiftClient.resource(kafka).createOrReplace();
        }

        informerManager.createKafkaInformer();

        ingressControllerManager.reconcileIngressControllers();

        List<IngressController> ingressControllers = openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).list().getItems();
        assertEquals(4, ingressControllers.size(), "Expected 4 IngressControllers: one per zone, and one multi-zone");

        // should just be 3 for 75 units
        ingressControllers.stream().map(ic -> ic.getSpec().getReplicas()).forEach(r -> assertEquals(3, r));
    }

    @Test
    public void testSummarize() {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        Kafka kafka = this.kafkaCluster.kafkaFrom(mk, null);
        int replicas = kafka.getSpec().getKafka().getReplicas();
        int instances = 4;

        LongSummaryStatistics egress = IngressControllerManager.summarize(Collections.nCopies(instances, kafka),
                KafkaCluster::getFetchQuota, () -> {throw new AssertionError();});
        long singleEgress = Quantity.getAmountInBytes(mk.getSpec().getCapacity().getEgressPerSec()).longValue()
                / replicas;
        assertEquals(singleEgress, egress.getMax());
        assertEquals(singleEgress * instances * replicas, egress.getSum());
    }

    @Test
    public void testIngressControllerReplicaCounts() {
        List<Node> nodes = buildNodes(9);

        assertEquals(1, ingressControllerManager.numReplicasForDefault(3000));
        assertEquals(1, ingressControllerManager.numReplicasForZone(new LongSummaryStatistics(), new LongSummaryStatistics(), 0, ZONE_PERCENTAGE));

        nodes = buildNodes(210);

        assertEquals(3, ingressControllerManager.numReplicasForDefault(160000));
        assertEquals(1, ingressControllerManager.numReplicasForZone(new LongSummaryStatistics(1, 0, 30000000, 1500000000), new LongSummaryStatistics(1, 0, 30000000, 1500000000), 0, ZONE_PERCENTAGE));
        assertEquals(3, ingressControllerManager.numReplicasForZone(new LongSummaryStatistics(), new LongSummaryStatistics(), 480000, ZONE_PERCENTAGE));

        nodes = buildNodes(310);

        long ingress = 50000000;
        assertEquals(5, ingressControllerManager.numReplicasForDefault(370000));
        assertEquals(4, ingressControllerManager.numReplicasForZone(new LongSummaryStatistics(1, 0, ingress, ingress*60), new LongSummaryStatistics(1, 0, ingress*2, ingress*120), 0, ZONE_PERCENTAGE));
    }

    @Test
    public void testIngressControllerReplicaCounts1() {
        List<Node> nodes = buildNodes(99);

        assertEquals(1, ingressControllerManager.numReplicasForDefault(3000*24));
        assertEquals(2, ingressControllerManager.numReplicasForZone(new LongSummaryStatistics(1, 0, Quantity.getAmountInBytes(Quantity.parse("50Mi")).longValue(), Quantity.getAmountInBytes(Quantity.parse("50Mi")).longValue()*24), new LongSummaryStatistics(1, 0, Quantity.getAmountInBytes(Quantity.parse("100Mi")).longValue(), Quantity.getAmountInBytes(Quantity.parse("100Mi")).longValue()*24), 0, ZONE_PERCENTAGE));
    }

    private List<Node> buildNodes(int nodeCount) {
        return IntStream.range(0, nodeCount).mapToObj(i ->
            new NodeBuilder()
                    .editOrNewMetadata()
                        .withName("z"+i)
                        .withLabels(Map.of(IngressControllerManager.WORKER_NODE_LABEL, "", IngressControllerManager.TOPOLOGY_KEY, "zone"+(i%3)))
                    .endMetadata()
                    .build()
        ).collect(Collectors.toList());
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

    @Test
    public void testIngressControllerHaProxyOptions() {
        QuarkusMock.installMockForType(Mockito.mock(InformerManager.class), InformerManager.class);

        ingressControllerManager.reconcileIngressControllers();

        var ingressController = openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).withName("kas").get();
        assertNotNull(ingressController);

        assertEquals("5s", ingressController.getMetadata().getAnnotations().get(IngressControllerManager.HARD_STOP_AFTER_ANNOTATION));
        assertEquals(60, ((Config) ingressController.getSpec().getUnsupportedConfigOverrides()).getAdditionalProperties().get("reloadInterval"));
    }

    @Test
    public void testIngressControllerPreservesOtherAnnotationsAndUnsupportedConfigOverrides() {
        QuarkusMock.installMockForType(Mockito.mock(InformerManager.class), InformerManager.class);

        ingressControllerManager.reconcileIngressControllers();

        var ingressController = openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).withName("kas").get();
        assertNotNull(ingressController);

        IngressController edit = new IngressControllerBuilder(ingressController)
                .editMetadata()
                .addToAnnotations("foo", "far")
                .endMetadata()
                .editSpec()
                .withNewConfigUnsupportedConfigOverrides()
                .addToAdditionalProperties("boo", "bar")
                .endConfigUnsupportedConfigOverrides()
                .endSpec()
                .build();
        openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).replace(edit);

        ingressControllerManager.reconcileIngressControllers();

        var updated = openShiftClient.operator().ingressControllers().inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).withName("kas").get();
        var updatedConfig = (Config) updated.getSpec().getUnsupportedConfigOverrides();

        // Expect that controller preserves the additions
        assertEquals("far", updated.getMetadata().getAnnotations().get("foo"));
        assertEquals("bar", updatedConfig.getAdditionalProperties().get("boo"));

        // and the expected options are present too.
        assertEquals("5s", updated.getMetadata().getAnnotations().get(IngressControllerManager.HARD_STOP_AFTER_ANNOTATION));
        assertEquals(60, updatedConfig.getAdditionalProperties().get("reloadInterval"));
    }


    @BeforeEach
    @AfterEach
    void cleanup() {
        ingressControllerManager.getRouteMatchLabels().clear();
        openShiftClient.resources(Node.class).delete();
        openShiftClient.resources(Kafka.class).inAnyNamespace().delete();
        openShiftClient.resources(IngressController.class).inNamespace(IngressControllerManager.INGRESS_OPERATOR_NAMESPACE).delete();
    }
}
