package org.bf2.operator.controllers;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.managers.SecuritySecretManager;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.operands.KafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ProfileBuilder;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatusBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class ManagedKafkaControllerTest {

    @Inject
    ManagedKafkaController mkController;

    @Inject
    ManagedKafkaAgentResourceClient managedKafkaAgent;

    @InjectMock
    SecuritySecretManager secretManager;

    @InjectMock
    StrimziManager strimziManager;

    @Inject
    KubernetesClient client;

    @Inject
    KafkaCluster kafkaCluster;

    @Test
    void shouldCreateStatus() throws InterruptedException {
        String id = UUID.randomUUID().toString();
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(id);
        mk.getMetadata().setName(id);
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        // create
        Context context = Mockito.mock(Context.class);

        Mockito.when(strimziManager.getStrimziVersion("strimzi-cluster-operator.v0.23.0"))
                .thenReturn(new StrimziVersionStatusBuilder()
                                .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions(mk.getSpec().getVersions().getKafka())
                        .build());
        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn(ManagedKafkaKeys.Labels.STRIMZI_VERSION);

        mkController.reconcile(mk, context);
        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Installing.name(), condition.getReason());

        mk.getMetadata().setLabels(Map.of(ManagedKafka.PROFILE_TYPE, "not valid"));
        mkController.reconcile(mk, context);
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());

        mk.getSpec().setDeleted(true);
        mkController.reconcile(mk, context);

        // should now be deleted
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Deleted.name(), condition.getReason());
    }

    @Test
    void testWrongVersions() throws InterruptedException {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        // create
        Context context = Mockito.mock(Context.class);

        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn(ManagedKafkaKeys.Labels.STRIMZI_VERSION);

        mkController.reconcile(mk, context);
        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());
        assertEquals("The requested Strimzi version strimzi-cluster-operator.v0.23.0 is not supported", condition.getMessage());

        Mockito.when(strimziManager.getStrimziVersion("strimzi-cluster-operator.v0.23.0"))
                .thenReturn(new StrimziVersionStatusBuilder()
                        .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions("3.0.0")
                        .build());

        mkController.reconcile(mk, context);
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());
        assertEquals("The requested Kafka version 2.7.0 is not supported by the Strimzi version strimzi-cluster-operator.v0.23.0", condition.getMessage());
    }

    @Test
    void testEnforceMax() {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        managedKafkaAgent.createOrReplace(new ManagedKafkaAgentBuilder()
                .withNewMetadata()
                .withName(ManagedKafkaAgentResourceClient.RESOURCE_NAME)
                .endMetadata()
                .withNewSpec()
                .addToCapacity("standard", new ProfileBuilder().withMaxNodes(6).build())
                .endSpec()
                .build());

        Context context = Mockito.mock(Context.class);

        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn("managedkafka.bf2.org/strimziVersion");
        Mockito.when(strimziManager.getStrimziVersion("strimzi-cluster-operator.v0.23.0"))
                .thenReturn(new StrimziVersionStatusBuilder()
                        .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions(mk.getSpec().getVersions().getKafka())
                        .build());

        // the first one should default to size one and be fine to place
        mkController.reconcile(mk, context);

        ManagedKafka mk2 = ManagedKafka.getDummyInstance(2);
        mk2.getMetadata().setUid(UUID.randomUUID().toString());
        mk2.getMetadata().setGeneration(1l);
        mk2.getMetadata().setLabels(Map.of(ManagedKafka.PROFILE_QUOTA_CONSUMED, String.valueOf(2)));
        mk2.getMetadata().setResourceVersion("1");

        mkController.reconcile(mk2, context);

        ManagedKafkaCondition condition = mk2.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Rejected.name(), condition.getReason());
        assertEquals("Cluster has insufficient resources", condition.getMessage());

        // delete the first managedkafka, and mk2 should stay rejected
        mk.getSpec().setDeleted(true);
        mkController.reconcile(mk, context);
        mkController.reconcile(mk2, context);
        condition = mk2.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Rejected.name(), condition.getReason());
        assertEquals("Cluster has insufficient resources", condition.getMessage());

        // mk-3 should fit
        mkController.reconcile(new ManagedKafkaBuilder(mk).editMetadata().withName("mk-3").endMetadata().build(), context);

        condition = mk2.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Rejected.name(), condition.getReason());
        assertEquals("Cluster has insufficient resources", condition.getMessage());
    }

    @Test
    void testReadyFalseWithReasonSuspended() throws InterruptedException {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        // create
        Context context = Mockito.mock(Context.class);

        Mockito.when(strimziManager.getStrimziVersion("strimzi-cluster-operator.v0.23.0"))
            .thenReturn(new StrimziVersionStatusBuilder()
                            .withVersion(mk.getSpec().getVersions().getStrimzi())
                    .withKafkaVersions(mk.getSpec().getVersions().getKafka())
                    .build());
        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn(ManagedKafkaKeys.Labels.STRIMZI_VERSION);

        mk.getMetadata().setLabels(Map.of(ManagedKafka.SUSPENDED_INSTANCE, "true"));

        mkController.reconcile(mk, context);

        assertEquals(1, mk.getStatus().getConditions().size());

        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Status.False.name(), condition.getStatus());
        assertEquals(ManagedKafkaCondition.Reason.Suspended.name(), condition.getReason());

        // Additional status information will be present even though the instance is not ready
        assertNotNull(mk.getStatus().getCapacity());
        assertNotNull(mk.getStatus().getVersions());
    }

    @Test
    void testMoveAnnotations() throws InterruptedException {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");
        mk.getMetadata().getAnnotations().put(ManagedKafkaKeys.Annotations.KAFKA_UPGRADE_START_TIMESTAMP, "now");
        Mockito.when(secretManager.masterSecretExists(mk)).thenReturn(true);

        // create
        Context context = Mockito.mock(Context.class);

        Mockito.when(strimziManager.getStrimziVersion("strimzi-cluster-operator.v0.23.0"))
                .thenReturn(new StrimziVersionStatusBuilder()
                                .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions(mk.getSpec().getVersions().getKafka())
                        .build());
        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn(ManagedKafkaKeys.Labels.STRIMZI_VERSION);

        // with no Kafka to target we'll just remove the annotations
        UpdateControl<ManagedKafka> updateControl = mkController.reconcile(mk, context);

        // no change / unset
        assertNull(mk.getStatus());
        // removed
        assertTrue(mk.getAnnotation(ManagedKafkaKeys.Annotations.KAFKA_UPGRADE_START_TIMESTAMP).isEmpty());

        assertTrue(updateControl.isUpdateResource());
        assertFalse(updateControl.isUpdateStatus());

        mk.getMetadata().getAnnotations().put(ManagedKafkaKeys.Annotations.KAFKA_UPGRADE_START_TIMESTAMP, "now");
        var kafkaResource = client.resource(kafkaCluster.kafkaFrom(mk, null));
        kafkaResource.createOrReplace();

        // with a Kafka the annotations will be moved
        updateControl = mkController.reconcile(mk, context);

        assertTrue(mk.getAnnotation(ManagedKafkaKeys.Annotations.KAFKA_UPGRADE_START_TIMESTAMP).isEmpty());

        assertTrue(updateControl.isUpdateResource());
        assertFalse(updateControl.isUpdateStatus());
        Kafka kafka = kafkaResource.fromServer().get();
        assertNotNull(kafka.getMetadata().getAnnotations().get(ManagedKafkaKeys.Annotations.KAFKA_UPGRADE_START_TIMESTAMP));
    }

    @AfterEach
    void cleanup() {
        managedKafkaAgent.delete();
        client.resources(Kafka.class).inAnyNamespace().delete();
    }

}
