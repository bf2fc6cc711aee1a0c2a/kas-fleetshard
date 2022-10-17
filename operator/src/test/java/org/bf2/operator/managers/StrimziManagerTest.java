package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.status.ConditionBuilder;
import io.strimzi.api.kafka.model.status.KafkaStatusBuilder;
import org.bf2.operator.ManagedKafkaKeys.Annotations;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.operands.KafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatus;
import org.bf2.operator.resources.v1alpha1.VersionsBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class StrimziManagerTest {

    @Inject
    KafkaResourceClient kafkaClient;

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    StrimziManager strimziManager;

    @Inject
    KafkaCluster kafkaCluster;

    @Inject
    InformerManager informerManager;

    @BeforeEach
    public void beforeEach() {
        // before each test clean Kubernetes server (no Deployments from other runs)
        this.server.before();
        this.strimziManager.clearStrimziVersions();

        this.informerManager.createKafkaInformer();
    }

    @Test
    public void testNotInstalledStrimziOperators() {
        List<StrimziVersionStatus> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(strimziVersions.isEmpty());
    }

    @Test
    public void testInstalledStrimziOperators() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", "2.7.0=kafka-2.7.0", true, true);
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", "2.7.0=kafka-2.7.0", true, true);

        List<StrimziVersionStatus> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v1", true));
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", true));
    }

    @Test
    public void testInstalledStrimziOperatorsWithVersionAnnotations() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", "2.7.0=kafka-2.7.0", Collections.emptyMap(), true, true, true);
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", "2.7.0=kafka-2.7.0\n2.8.0=kafka-2.8.0", Collections.emptyMap(), true, true, true);

        List<StrimziVersionStatus> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(checkKafkaVersions(strimziVersions, "strimzi-cluster-operator.v1", List.of("2.7.0")));
        assertTrue(checkKafkaVersions(strimziVersions, "strimzi-cluster-operator.v2", List.of("2.7.0", "2.8.0")));
    }

    @Test
    public void testInstalledStrimziOperatorsWithRelatedImages() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", "2.7.0=kafka-2.7.0", Map.of("canary", "canary:1.0"), true, true, true);
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", "2.7.0=kafka-2.7.0", Map.of("canary", "canary:2.0", "admin-server", "admin-server:latest"), true, true, true);

        assertEquals("canary:1.0", this.strimziManager.getRelatedImage("strimzi-cluster-operator.v1", "canary"));
        assertEquals("canary:2.0", this.strimziManager.getRelatedImage("strimzi-cluster-operator.v2", "canary"));
        assertEquals("admin-server:latest", this.strimziManager.getRelatedImage("strimzi-cluster-operator.v2", "admin-server"));
    }

    @Test
    public void testInstalledStrimziOperatorsWithPendingVersions() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", "2.7.0=kafka-2.7.0", true, true);
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", "2.7.0=kafka-2.7.0", true, true);

        List<StrimziVersionStatus> strimziVersions = this.strimziManager.getStrimziVersions();
        assertEquals(2, strimziVersions.size());

        // we will, or have approved these next versions
        this.strimziManager.setStrimziPendingInstallationVersions(Arrays.asList("strimzi-cluster-operator.v2", "strimzi-cluster-operator.v3"));

        // v1 should drop out
        strimziVersions = this.strimziManager.getStrimziVersions();
        assertEquals(1, strimziVersions.size());
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", true));

        // the old versions go away
        uninstallStrimziOperator("strimzi-cluster-operator.v1", "ns-1");
        uninstallStrimziOperator("strimzi-cluster-operator.v2", "ns-2");

        // v2 should stay, but won't be ready until installed
        strimziVersions = this.strimziManager.getStrimziVersions();
        assertEquals(1, strimziVersions.size());
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", false));

        // install the next state
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", "2.7.0=kafka-2.7.0", true, true);
        installStrimziOperator("strimzi-cluster-operator.v3", "ns-2", "2.7.0=kafka-2.7.0", true, true);

        // before and after the bundle manager clears the pending versions, we should see the next state
        strimziVersions = this.strimziManager.getStrimziVersions();
        assertEquals(2, strimziVersions.size());
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", true));
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v3", true));

        this.strimziManager.clearStrimziPendingInstallationVersions();

        strimziVersions = this.strimziManager.getStrimziVersions();
        assertEquals(2, strimziVersions.size());
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", true));
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v3", true));
    }

    @Test
    public void testInstalledStrimziOperatorsKafkaVersions() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", "2.7.0=kafka-2.7.0", true, true);
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", "2.7.0=kafka-2.7.0\n2.8.0=kafka-2.8.0", true, true);

        List<StrimziVersionStatus> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v1", true));
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", true));

        List<String> v1KafkaExpected = Collections.singletonList("2.7.0");
        List<String> v2KafkaExpected = Arrays.asList("2.7.0", "2.8.0");
        List<String> v1KafkaCurrent = strimziVersions.stream().filter(svs -> svs.getVersion().equals("strimzi-cluster-operator.v1")).map(StrimziVersionStatus::getKafkaVersions).findFirst().get();
        List<String> v2KafkaCurrent = strimziVersions.stream().filter(svs -> svs.getVersion().equals("strimzi-cluster-operator.v2")).map(StrimziVersionStatus::getKafkaVersions).findFirst().get();
        Assertions.assertIterableEquals(v1KafkaExpected, v1KafkaCurrent);
        Assertions.assertIterableEquals(v2KafkaExpected, v2KafkaCurrent);

        List<String> v1KafkaIbpExpected = Collections.singletonList("2.7");
        List<String> v2KafkaIbpExpected = Arrays.asList("2.7", "2.8");
        List<String> v1KafkaIbpCurrent = strimziVersions.stream().filter(svs -> svs.getVersion().equals("strimzi-cluster-operator.v1")).map(StrimziVersionStatus::getKafkaIbpVersions).findFirst().get();
        List<String> v2KafkaIbpCurrent = strimziVersions.stream().filter(svs -> svs.getVersion().equals("strimzi-cluster-operator.v2")).map(StrimziVersionStatus::getKafkaIbpVersions).findFirst().get();
        Assertions.assertIterableEquals(v1KafkaIbpExpected, v1KafkaIbpCurrent);
        Assertions.assertIterableEquals(v2KafkaIbpExpected, v2KafkaIbpCurrent);
    }

    @Test
    public void testNotReadyStrimziOperators() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", "2.7.0=kafka-2.7.0", true, true);
        // setting the operator v2 as not ready
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", "2.7.0=kafka-2.7.0", false, true);

        List<StrimziVersionStatus> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v1", true));
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", false));
    }

    @Test
    public void testNotDiscoverableStrimziOperators() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", "2.7.0=kafka-2.7.0", true, true);
        // setting the operator v2 as not discoverable
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", "2.7.0=kafka-2.7.0", true, false);

        List<StrimziVersionStatus> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v1", true));
        assertFalse(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", true));
    }

    @Test
    public void testUninstallingStrimziOperator() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", "2.7.0=kafka-2.7.0", true, true);
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", "2.7.0=kafka-2.7.0", true, true);

        List<StrimziVersionStatus> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v1", true));
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", true));

        uninstallStrimziOperator("strimzi-cluster-operator.v2", "ns-2");
        strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v1", true));
        assertFalse(checkStrimziVersion(strimziVersions, "strimzi-cluster-operator.v2", true));
    }

    @Test
    public void testStrimziVersionChange() {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getSpec().getVersions().setStrimzi("strimzi-cluster-operator.v1");

        Kafka kafka = this.kafkaCluster.kafkaFrom(mk, null);
        kafkaClient.create(kafka);
        // Kafka reconcile not paused and current label version as the ManagedKafka one
        assertFalse(kafka.getMetadata().getAnnotations().containsKey(StrimziManager.STRIMZI_PAUSE_RECONCILE_ANNOTATION));
        assertEquals(kafka.getMetadata().getLabels().get(this.strimziManager.getVersionLabel()), mk.getSpec().getVersions().getStrimzi());

        // ManagedKafka and Kafka updated their status information
        mk.setStatus(new ManagedKafkaStatusBuilder().withVersions(new VersionsBuilder().withStrimzi("strimzi-cluster-operator.v1").build()).build());
        kafka.setStatus(new KafkaStatusBuilder().withConditions(new ConditionBuilder().withType("Ready").withStatus("True").build()).build());
        kafkaClient.createOrReplace(kafka);

        // ask for a Strimzi version change on ManagedKafka
        mk.getSpec().getVersions().setStrimzi("strimzi-cluster-operator.v2");

        kafka = this.kafkaCluster.kafkaFrom(mk, kafka);
        // Kafka reconcile paused but label is still the current version
        assertTrue(kafka.getMetadata().getAnnotations().containsKey(StrimziManager.STRIMZI_PAUSE_RECONCILE_ANNOTATION));
        assertTrue(kafka.getMetadata().getAnnotations().containsKey(Annotations.STRIMZI_PAUSE_REASON));
        assertEquals(kafka.getMetadata().getLabels().get(this.strimziManager.getVersionLabel()), mk.getStatus().getVersions().getStrimzi());

        // nothing should change after an intermediate reconcile
        kafka = this.kafkaCluster.kafkaFrom(mk, kafka);
        assertTrue(kafka.getMetadata().getAnnotations().containsKey(Annotations.STRIMZI_PAUSE_REASON));

        // Kafka moves to be paused
        kafka.setStatus(new KafkaStatusBuilder().withConditions(new ConditionBuilder().withType("ReconciliationPaused").withStatus("True").build()).build());
        kafkaClient.createOrReplace(kafka);

        kafka = this.kafkaCluster.kafkaFrom(mk, kafka);
        // Kafka reconcile not paused and Kafka label updated to requested Strimzi version
        assertFalse(kafka.getMetadata().getAnnotations().containsKey(StrimziManager.STRIMZI_PAUSE_RECONCILE_ANNOTATION));
        // the pause reason should stay until strimzi updates to ready
        assertTrue(kafka.getMetadata().getAnnotations().containsKey(Annotations.STRIMZI_PAUSE_REASON));
        assertEquals(kafka.getMetadata().getLabels().get(this.strimziManager.getVersionLabel()), "strimzi-cluster-operator.v2");
    }

    private void installStrimziOperator(String name, String namespace, String kafkaImages, boolean ready, boolean discoverable) {
        installStrimziOperator(name, namespace, kafkaImages, Collections.emptyMap(), ready, discoverable, false);
    }

    /**
     * Install a Strimzi operator creating the corresponding Deployment in the specified namespace
     * and making it ready and discoverable if requested
     *
     * @param name Strimzi operator deployment name
     * @param namespace namespace where the Strimzi operator is installed
     * @param kafkaImages kafka images supported by the Strimzi operator
     * @param ready if the Strimzi operator has to be ready
     * @param discoverable if the Strimzi operator should be discoverable by the Strimzi manager
     */
    private void installStrimziOperator(String name, String namespace, String kafkaImages, Map<String, String> relatedImages, boolean ready, boolean discoverable, boolean versionsAnnotations) {
        Map<String, String> labels = new HashMap<>(+2);
        labels.put("name", name);
        if (discoverable) {
            labels.put("app.kubernetes.io/part-of", "managed-kafka");
        }

        Map<String, String> annotations = new HashMap<>();

        if (versionsAnnotations && kafkaImages != null) {
            annotations.put(Annotations.KAFKA_IMAGES, kafkaImages);
        }

        if (!relatedImages.isEmpty()) {
            annotations.put(Annotations.RELATED_IMAGES, Serialization.asJson(relatedImages));
        }

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(name)
                        .withLabels(labels)
                    .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withNewSelector()
                        .withMatchLabels(Collections.singletonMap("name", name))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                            .withAnnotations(annotations)
                        .endMetadata()
                        .withNewSpec()
                            .withContainers(new ContainerBuilder()
                                    .withName(name)
                                    .withImage(name + "-image")
                                    .build()
                            )
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .withNewStatus()
                    .withReplicas(1)
                    .withAvailableReplicas(ready ? 1 : 0)
                    .withReadyReplicas(ready ? 1 : 0)
                .endStatus()
                .build();

        if (!versionsAnnotations && kafkaImages != null) {
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0)
                    .setEnv(Collections.singletonList(new EnvVarBuilder().withName(StrimziManager.KAFKA_IMAGES_ENVVAR).withValue(kafkaImages).build()));
        }

        this.server.getClient().apps().deployments().inNamespace(namespace).create(deployment);

        if (discoverable) {
            this.strimziManager.updateStrimziVersion(deployment);
        }
    }

    /**
     * Uninstall a specific Strimzi operator deleting the corresponding Deployment
     *
     * @param name Strimzi operator deployment name
     * @param namespace namespace where the Strimzi operator is installed
     */
    private void uninstallStrimziOperator(String name, String namespace) {
        Resource<Deployment> depResource = this.server.getClient().apps().deployments().inNamespace(namespace).withName(name);
        // only if "discoverable" was added to the Strimzi manager list
        if (depResource.get().getMetadata().getLabels().containsKey("app.kubernetes.io/part-of")) {
            this.strimziManager.deleteStrimziVersion(depResource.get());
        }
        depResource.delete();
    }

    /**
     * Check if a Strimzi version is in the provided ready state
     *
     * @param strimziVersions list of Strimzi versions where to check
     * @param version Strimzi version to check
     * @param isReady ready status to check for the Strimzi version
     * @return if a Strimzi version is in the provided ready state
     */
    private boolean checkStrimziVersion(List<StrimziVersionStatus> strimziVersions, String version, boolean isReady) {
        return strimziVersions.stream().anyMatch(svs -> version.equals(svs.getVersion()) && svs.isReady() == isReady);
    }

    /**
     * Check if the list of Kafka versions is present in one of the strimziVersions
     *
     * @param strimziVersions list of Strimzi versions where to check
     * @param versions Kafka versions version to check
     * @return if the Kafka versions are in the provided status list
     */
    private boolean checkKafkaVersions(List<StrimziVersionStatus> strimziVersions, String strimziVersion, List<String> versions) {
        return strimziVersions.stream()
                .filter(svs -> strimziVersion.equals(svs.getVersion()))
                .allMatch(svs -> svs.getKafkaVersions().equals(versions));
    }
}
