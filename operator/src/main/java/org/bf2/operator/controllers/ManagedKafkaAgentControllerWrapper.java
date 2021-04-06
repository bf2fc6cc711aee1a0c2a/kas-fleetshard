package org.bf2.operator.controllers;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;

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
public class ManagedKafkaAgentControllerWrapper implements ResourceController<ManagedKafkaAgent> {

    @Inject
    ManagedKafkaAgentController controller;

    @Override
    public DeleteControl deleteResource(ManagedKafkaAgent resource, Context<ManagedKafkaAgent> context) {
        return controller.deleteResource(resource, context);
    }

    @Override
    public UpdateControl<ManagedKafkaAgent> createOrUpdateResource(ManagedKafkaAgent resource, Context<ManagedKafkaAgent> context) {
        return controller.createOrUpdateResource(resource, context);
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        controller.init(eventSourceManager);
    }

}
