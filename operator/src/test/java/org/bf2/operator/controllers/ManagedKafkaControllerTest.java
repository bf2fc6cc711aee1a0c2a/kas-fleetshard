package org.bf2.operator.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.UUID;

import javax.inject.Inject;

import org.bf2.operator.events.ResourceEvent;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.test.mock.QuarkusKubeMockServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.processing.event.EventList;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEvent;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTestResource(QuarkusKubeMockServer.class)
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

        mkController.createOrUpdateResource(mk, context);
        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Installing.name(), condition.getReason());

        mk.getSpec().setDeleted(true);
        // this simulates, but not exactly an issue seen with older logic
        // essentially there "last event" of the delete is something other than a deployment or a kafka
        // it should still trigger the update of the status
        Mockito.when(context.getEvents())
                .thenReturn(new EventList(Arrays.asList(new ResourceEvent.ServiceEvent(new ServiceBuilder()
                        .withNewMetadata()
                        .withOwnerReferences(new OwnerReferenceBuilder().withUid(mk.getMetadata().getUid()).build())
                        .endMetadata()
                        .build(), null))));
        mkController.createOrUpdateResource(mk, context);

        // should now be deleted
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Deleted.name(), condition.getReason());
    }

}
