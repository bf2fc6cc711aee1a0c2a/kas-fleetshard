package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Objects;

@ApplicationScoped
public class ResourceEventSource extends AbstractEventSource implements ResourceEventHandler<HasMetadata> {

    @Inject
    Logger log;

    @Override
    public void onAdd(HasMetadata resource) {
        log.debugf("Add event received for %s %s/%s", resource.getClass().getName(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
        handleEvent(resource);
    }

    @Override
    public void onUpdate(HasMetadata oldResource, HasMetadata newResource) {
        if (Objects.equals(oldResource.getMetadata().getResourceVersion(), newResource.getMetadata().getResourceVersion())) {
            return; // no need to handle an event where nothing has changed
        }
        log.debugf("Update event received for %s %s/%s", oldResource.getClass().getName(), oldResource.getMetadata().getNamespace(), oldResource.getMetadata().getName());
        handleEvent(newResource);
    }

    @Override
    public void onDelete(HasMetadata resource, boolean deletedFinalStateUnknown) {
        log.debugf("Delete event received for %s %s/%s with deletedFinalStateUnknown %s", resource.getClass().getName(),
                resource.getMetadata().getNamespace(), resource.getMetadata().getName(), deletedFinalStateUnknown);
        handleEvent(resource);
    }

    protected void handleEvent(HasMetadata resource) {
        // the operator may not have inited yet
        if (eventHandler != null
                // observability secret does not have an owner reference
                && !resource.getMetadata().getOwnerReferences().isEmpty()) {
            eventHandler.handleEvent(new ResourceEvent<HasMetadata>(resource, this));
        }
    }

}
