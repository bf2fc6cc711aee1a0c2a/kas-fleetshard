package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.processing.event.AbstractEvent;

public class DeploymentEvent extends AbstractEvent {

    private Deployment deployment;

    public DeploymentEvent(Deployment deployment, DeploymentEventSource deploymentEventSource) {
        super(deployment.getMetadata().getOwnerReferences().get(0).getUid(), deploymentEventSource);
        this.deployment = deployment;
    }

    public Deployment getDeployment() {
        return deployment;
    }
}
