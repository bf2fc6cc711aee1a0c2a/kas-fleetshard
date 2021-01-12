package org.bf2.operator;

import io.javaoperatorsdk.operator.api.*;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import jdk.jfr.Name;
import org.bf2.operator.api.v1alpha1.ManagedKafka;

@Controller(crdName = "managedkafkas.org.bf2")
@Name("Agent operator")
public class ManagedKafkaController  implements ResourceController<ManagedKafka> {

    @Override
    public DeleteControl deleteResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        return null;
    }

    @Override
    public UpdateControl<ManagedKafka> createOrUpdateResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        return null;
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {

    }

    @Override
    public String getName() {
        return null;
    }
}
