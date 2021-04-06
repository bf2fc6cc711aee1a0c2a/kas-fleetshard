package org.bf2.operator.controllers;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;

/**
 * Workaround for https://github.com/java-operator-sdk/java-operator-sdk/issues/393
 */
@Controller
public class ManagedKafkaControllerWrapper implements ResourceController<ManagedKafka> {

    @Inject
    ManagedKafkaController controller;

    @Override
    public DeleteControl deleteResource(ManagedKafka resource, Context<ManagedKafka> context) {
        return controller.deleteResource(resource, context);
    }

    @Override
    public UpdateControl<ManagedKafka> createOrUpdateResource(ManagedKafka resource, Context<ManagedKafka> context) {
        return controller.createOrUpdateResource(resource, context);
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        controller.init(eventSourceManager);
    }

}
