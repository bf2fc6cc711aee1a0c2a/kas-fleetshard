package org.bf2.sync.informer;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.function.BiConsumer;

public class CustomResourceEventHandlerTest {

    @Test public void testAdd() {
        BiConsumer<ManagedKafka, ManagedKafka> consumer = Mockito.mock(BiConsumer.class);

        CustomResourceEventHandler<ManagedKafka> handler = new CustomResourceEventHandler<>(consumer);

        ManagedKafka mk = new ManagedKafka();

        handler.onAdd(mk);

        Mockito.verify(consumer).accept(null, mk);
    }

}
