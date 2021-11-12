package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import io.javaoperatorsdk.operator.processing.event.DefaultEvent;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class ResourceEventSource extends AbstractEventSource implements ResourceEventHandler<HasMetadata> {

    @Inject
    Logger log;

    @Override
    public void onAdd(HasMetadata resource) {
        log.debugf("Add event received for %s %s/%s", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
        handleEvent(resource, Watcher.Action.ADDED);
    }

    @Override
    public void onUpdate(HasMetadata oldResource, HasMetadata newResource) {
        log.debugf("Update event received for %s %s/%s", oldResource.getKind(), oldResource.getMetadata().getNamespace(), oldResource.getMetadata().getName());
        handleEvent(newResource, Watcher.Action.MODIFIED);
    }

    @Override
    public void onDelete(HasMetadata resource, boolean deletedFinalStateUnknown) {
        log.debugf("Delete event received for %s %s/%s with deletedFinalStateUnknown %s", resource.getKind(),
                resource.getMetadata().getNamespace(), resource.getMetadata().getName(), deletedFinalStateUnknown);
        handleEvent(resource, Watcher.Action.DELETED);
    }

    protected void handleEvent(HasMetadata resource, Watcher.Action action) {
        // the operator may not have inited yet
        if (eventHandler != null) {
            if(resource.getMetadata().getOwnerReferences().isEmpty()) {
                log.warnf("%s %s/%s does not have OwnerReference", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
            }
            eventHandler.handleEvent(new ResourceEvent<HasMetadata>(resource, this, action));
        }
    }

    public void handleEvent(CustomResource resource) {
        if (eventHandler != null) {
            eventHandler.handleEvent(new DefaultEvent(resource.getMetadata().getUid(), this));
        }
    }
}
