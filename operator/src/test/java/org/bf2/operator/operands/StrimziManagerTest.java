package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.status.ConditionBuilder;
import io.strimzi.api.kafka.model.status.KafkaStatusBuilder;
import org.bf2.operator.StrimziManager;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.operator.resources.v1alpha1.VersionsBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

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

    @Inject
    KubernetesClient client;

    @Inject
    StrimziManager strimziManager;

    @Inject
    KafkaCluster kafkaCluster;

    @AfterEach
    public void clean() {
        DeploymentList deploymentList = this.client.apps().deployments().inAnyNamespace().list();
        for (Deployment deployment : deploymentList.getItems()) {
            uninstallStrimziOperator(deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
        }
    }

    @Test
    public void testNotInstalledStrimziOperators() {
        List<String> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(strimziVersions.isEmpty());
    }

    @Test
    public void testInstalledStrimziOperators() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", true, true);
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", true, true);

        List<String> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(strimziVersions.contains("strimzi-cluster-operator.v1"));
        assertTrue(strimziVersions.contains("strimzi-cluster-operator.v2"));
    }

    @Test
    public void testNotReadyStrimziOperators() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", true, true);
        // setting the operator v2 as not ready
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", false, true);

        List<String> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(strimziVersions.contains("strimzi-cluster-operator.v1"));
    }

    @Test
    public void testNotDiscoverableStrimziOperators() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", true, true);
        // setting the operator v2 as not discoverable
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", true, false);

        List<String> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(strimziVersions.contains("strimzi-cluster-operator.v1"));
    }

    @Test
    public void testUninstallingStrimziOperator() {
        installStrimziOperator("strimzi-cluster-operator.v1", "ns-1", true, true);
        installStrimziOperator("strimzi-cluster-operator.v2", "ns-2", true, true);

        List<String> strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(strimziVersions.contains("strimzi-cluster-operator.v1"));
        assertTrue(strimziVersions.contains("strimzi-cluster-operator.v2"));

        uninstallStrimziOperator("strimzi-cluster-operator.v2", "ns-2");
        strimziVersions = this.strimziManager.getStrimziVersions();
        assertTrue(strimziVersions.contains("strimzi-cluster-operator.v1"));
    }

    @Test
    public void testStrimziVersionChange() {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getSpec().getVersions().setStrimzi("strimzi-cluster-operator.v1");

        Kafka kafka = this.kafkaCluster.kafkaFrom(mk, null);
        kafkaClient.create(kafka);
        // Kafka reconcile not paused and current label version as the ManagedKafka one
        assertFalse(kafka.getMetadata().getAnnotations().containsKey("strimzi.io/pause-reconciliation"));
        assertEquals(kafka.getMetadata().getLabels().get(this.strimziManager.getVersionLabel()), mk.getSpec().getVersions().getStrimzi());

        // ManagedKafka and Kafka updated their status information
        mk.setStatus(new ManagedKafkaStatusBuilder().withVersions(new VersionsBuilder().withStrimzi("strimzi-cluster-operator.v1").build()).build());
        kafka.setStatus(new KafkaStatusBuilder().withConditions(new ConditionBuilder().withType("Ready").withStatus("True").build()).build());
        kafkaClient.updateStatus(kafka);

        // ask for a Strimzi version change on ManagedKafka
        mk.getSpec().getVersions().setStrimzi("strimzi-cluster-operator.v2");

        kafka = this.kafkaCluster.kafkaFrom(mk, kafka);
        // Kafka reconcile paused but label is still the current version
        assertTrue(kafka.getMetadata().getAnnotations().containsKey("strimzi.io/pause-reconciliation"));
        assertEquals(kafka.getMetadata().getLabels().get(this.strimziManager.getVersionLabel()), mk.getStatus().getVersions().getStrimzi());

        // Kafka moves to be paused
        kafka.setStatus(new KafkaStatusBuilder().withConditions(new ConditionBuilder().withType("ReconciliationPaused").withStatus("True").build()).build());
        kafkaClient.updateStatus(kafka);

        kafka = this.kafkaCluster.kafkaFrom(mk, kafka);
        // Kafka reconcile not paused and Kafka label updated to requested Strimzi version
        assertFalse(kafka.getMetadata().getAnnotations().containsKey("strimzi.io/pause-reconciliation"));
        assertEquals(kafka.getMetadata().getLabels().get(this.strimziManager.getVersionLabel()), "strimzi-cluster-operator.v2");
    }

    /**
     * Install a Strimzi operator creating the corresponding Deployment and ReplicaSet in the specified namespace
     * and making it ready and discoverable if requested
     *
     * @param name Strimzi operator deployment/replicaset name
     * @param namespace namespace where the Strimzi operator is installed
     * @param ready if the Strimzi operator has to be ready
     * @param discoverable if the Strimzi operator should be discoverable by the Strimzi manager
     */
    private void installStrimziOperator(String name, String namespace, boolean ready, boolean discoverable) {
        Map<String, String> labels = new HashMap<>(+2);
        labels.put("name", name);
        if (discoverable) {
            labels.put("app.kubernetes.io/part-of", "managed-kafka");
        }

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(name)
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
                    .withReadyReplicas(ready ? 1 : 0)
                .endStatus()
                .build();

        ReplicaSet replicaSet = new ReplicaSetBuilder()
                .withNewMetadata()
                    .withName(name + "-replicaset")
                    .withNamespace(namespace)
                    .withLabels(labels)
                    .withOwnerReferences(new OwnerReferenceBuilder()
                            .withName(name)
                            .build()
                    )
                .endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withNewSelector()
                        .withMatchLabels(Collections.singletonMap("name", name))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                        .withLabels(labels)
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
                .build();

        this.client.apps().deployments().inNamespace(namespace).create(deployment);
        this.client.apps().replicaSets().inNamespace(namespace).create(replicaSet);
    }

    /**
     * Uninstall a specific Strimzi operator deleting the corresponding Deployment and ReplicaSet
     *
     * @param name Strimzi operator deployment/replicaset name
     * @param namespace namespace where the Strimzi operator is installed
     */
    private void uninstallStrimziOperator(String name, String namespace) {
        this.client.apps().replicaSets().inNamespace(namespace).withName(name).delete();
        this.client.apps().deployments().inNamespace(namespace).withName(name).delete();
    }
}
