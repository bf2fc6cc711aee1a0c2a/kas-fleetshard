package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.processing.event.EventList;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEvent;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.operator.events.ResourceEvent;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatusBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class ManagedKafkaControllerTest {

    @Inject
    ManagedKafkaController mkController;

    @Test
    void shouldCreateStatus() throws InterruptedException {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        // create
        Context<ManagedKafka> context = Mockito.mock(Context.class);
        Mockito.when(context.getEvents())
                .thenReturn(new EventList(Arrays.asList(new CustomResourceEvent(Action.ADDED, mk, null))));

        StrimziManager strimziManager = Mockito.mock(StrimziManager.class);
        Mockito.when(strimziManager.getStrimziVersions())
                .thenReturn(Collections.singletonList(new
                                StrimziVersionStatusBuilder()
                                .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions(mk.getSpec().getVersions().getKafka())
                        .build()));
        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn("managedkafka.bf2.org/strimziVersion");

        QuarkusMock.installMockForType(strimziManager, StrimziManager.class);

        mkController.createOrUpdateResource(mk, context);
        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Installing.name(), condition.getReason());

        mk.getSpec().setDeleted(true);
        // this simulates, but not exactly an issue seen with older logic
        // essentially there "last event" of the delete is something other than a deployment or a kafka
        // it should still trigger the update of the status
        Mockito.when(context.getEvents())
                .thenReturn(new EventList(Arrays.asList(new ResourceEvent<>(new ServiceBuilder()
                        .withNewMetadata()
                        .withOwnerReferences(new OwnerReferenceBuilder().withUid(mk.getMetadata().getUid()).build())
                        .endMetadata()
                        .build(), null, Watcher.Action.DELETED))));
        mkController.createOrUpdateResource(mk, context);

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
        Context<ManagedKafka> context = Mockito.mock(Context.class);
        Mockito.when(context.getEvents())
                .thenReturn(new EventList(Arrays.asList(new CustomResourceEvent(Action.ADDED, mk, null))));

        StrimziManager strimziManager = Mockito.mock(StrimziManager.class);
        Mockito.when(strimziManager.getStrimziVersions())
                .thenReturn(Collections.singletonList(new
                        StrimziVersionStatusBuilder()
                        .withVersion("strimzi-cluster-operator.v0.24.0")
                        .withKafkaVersions(mk.getSpec().getVersions().getKafka())
                        .build()));
        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn("managedkafka.bf2.org/strimziVersion");

        QuarkusMock.installMockForType(strimziManager, StrimziManager.class);

        mkController.createOrUpdateResource(mk, context);
        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());
        assertEquals("The requested Strimzi version strimzi-cluster-operator.v0.23.0 is not supported", condition.getMessage());

        Mockito.when(strimziManager.getStrimziVersions())
                .thenReturn(Collections.singletonList(new
                        StrimziVersionStatusBuilder()
                        .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions("3.0.0")
                        .build()));

        mkController.createOrUpdateResource(mk, context);
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());
        assertEquals("The requested Kafka version 2.7.0 is not supported by the Strimzi version strimzi-cluster-operator.v0.23.0", condition.getMessage());
    }

    @Test
    void storageCalculation() throws InterruptedException {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        InformerManager informerManager = Mockito.mock(InformerManager.class);

        QuarkusMock.installMockForType(informerManager, InformerManager.class);
        Mockito.when(informerManager.getPvcsInNamespace(Mockito.anyString())).thenReturn(List.of());

        // there's no pvcs, should be 0
        assertEquals("0.000", mkController.calculateRetentionSize(mk).getAmount());

        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder().withNewStatus().addToCapacity("storage", Quantity.parse("123Gi")).endStatus().build();
        Mockito.when(informerManager.getPvcsInNamespace(Mockito.anyString())).thenReturn(List.of(pvc, pvc));

        // should be the sum in Gi, less the padding
        assertEquals("221.400", mkController.calculateRetentionSize(mk).getAmount());
    }

}
