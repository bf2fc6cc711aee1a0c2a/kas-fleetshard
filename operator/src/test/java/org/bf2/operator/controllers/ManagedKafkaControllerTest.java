package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
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
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.operands.KafkaInstanceConfigurations;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatusBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class ManagedKafkaControllerTest {

    @Inject
    ManagedKafkaController mkController;

    @Test
    void shouldCreateStatus() throws InterruptedException {
        String id = UUID.randomUUID().toString();
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(id);
        mk.getMetadata().setName(id);
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        // create
        Context<ManagedKafka> context = Mockito.mock(Context.class);
        Mockito.when(context.getEvents())
                .thenReturn(new EventList(Arrays.asList(new CustomResourceEvent(Action.ADDED, mk, null))));

        StrimziManager strimziManager = Mockito.mock(StrimziManager.class);
        Mockito.when(strimziManager.getStrimziVersion("strimzi-cluster-operator.v0.23.0"))
                .thenReturn(new StrimziVersionStatusBuilder()
                                .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions(mk.getSpec().getVersions().getKafka())
                        .build());
        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn("managedkafka.bf2.org/strimziVersion");

        QuarkusMock.installMockForType(strimziManager, StrimziManager.class);

        mkController.createOrUpdateResource(mk, context);
        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Installing.name(), condition.getReason());

        mk.getMetadata().setLabels(Map.of(KafkaInstanceConfigurations.PROFILE_TYPE, "not valid"));
        mkController.createOrUpdateResource(mk, context);
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());

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
        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn("managedkafka.bf2.org/strimziVersion");

        QuarkusMock.installMockForType(strimziManager, StrimziManager.class);

        mkController.createOrUpdateResource(mk, context);
        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());
        assertEquals("The requested Strimzi version strimzi-cluster-operator.v0.23.0 is not supported", condition.getMessage());

        Mockito.when(strimziManager.getStrimziVersion("strimzi-cluster-operator.v0.23.0"))
                .thenReturn(new StrimziVersionStatusBuilder()
                        .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions("3.0.0")
                        .build());

        mkController.createOrUpdateResource(mk, context);
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());
        assertEquals("The requested Kafka version 2.7.0 is not supported by the Strimzi version strimzi-cluster-operator.v0.23.0", condition.getMessage());
    }

}
