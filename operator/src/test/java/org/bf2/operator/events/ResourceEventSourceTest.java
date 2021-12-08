package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.javaoperatorsdk.operator.processing.event.Event;
import io.javaoperatorsdk.operator.processing.event.EventHandler;
import io.quarkus.test.junit.QuarkusTest;
import org.bf2.common.OperandUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ResourceEventSourceTest {

    @Inject
    ResourceEventSource resourceEventSource;

    @Test void testOwnerReferenceRemoved() {
        EventHandler mockEventHandler = Mockito.mock(EventHandler.class);
        resourceEventSource.setEventHandler(mockEventHandler);

        ConfigMap parent = new ConfigMapBuilder().withNewMetadata().withName("parent").withUid("uid").endMetadata().build();

        ConfigMap old = new ConfigMapBuilder().withNewMetadata().withName("old").withNamespace("ns").endMetadata().build();
        OperandUtils.setAsOwner(parent, old);
        ConfigMap removed = new ConfigMapBuilder().withNewMetadata().withName("new").withNamespace("ns").endMetadata().build();

        resourceEventSource.onUpdate(old, removed);

        // make sure we know that the old was modified

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        Mockito.verify(mockEventHandler).handleEvent(eventCaptor.capture());

        assertEquals("uid", eventCaptor.getValue().getRelatedCustomResourceUid());
    }

}
