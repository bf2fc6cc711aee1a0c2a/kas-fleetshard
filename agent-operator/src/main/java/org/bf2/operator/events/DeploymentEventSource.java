package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DeploymentEventSource extends AbstractEventSource implements ResourceEventHandler<Deployment> {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEventSource.class);

    @Override
    public void onAdd(Deployment deployment) {
        log.info("Add event received for Deployment {}/{}", deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
        handleEvent(deployment);
    }

    @Override
    public void onUpdate(Deployment oldDeployment, Deployment newDeployment) {
        log.info("Update event received for Deployment {}/{}", oldDeployment.getMetadata().getNamespace(), oldDeployment.getMetadata().getName());
        handleEvent(newDeployment);
    }

    @Override
    public void onDelete(Deployment deployment, boolean deletedFinalStateUnknown) {
        log.info("Delete event received for Deployment {}/{}", deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
        handleEvent(deployment);
    }

    private void handleEvent(Deployment deployment) {
        if (eventHandler == null) {
            return;
        }
        eventHandler.handleEvent(new DeploymentEvent(deployment, this));
    }
}
