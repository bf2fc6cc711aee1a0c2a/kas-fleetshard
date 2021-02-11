package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class DeploymentEventSource extends AbstractEventSource implements ResourceEventHandler<Deployment> {

    @Inject
    Logger log;

    @Override
    public void onAdd(Deployment deployment) {
        log.infof("Add event received for Deployment %s/%s", deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
        handleEvent(deployment);
    }

    @Override
    public void onUpdate(Deployment oldDeployment, Deployment newDeployment) {
        log.infof("Update event received for Deployment %s/%s", oldDeployment.getMetadata().getNamespace(), oldDeployment.getMetadata().getName());
        handleEvent(newDeployment);
    }

    @Override
    public void onDelete(Deployment deployment, boolean deletedFinalStateUnknown) {
        log.infof("Delete event received for Deployment %s/%s", deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
        handleEvent(deployment);
    }

    private void handleEvent(Deployment deployment) {
        eventHandler.handleEvent(new DeploymentEvent(deployment, this));
    }
}
