package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.AbstractEventSource;
import io.javaoperatorsdk.operator.processing.event.source.controller.ResourceAction;
import io.javaoperatorsdk.operator.processing.event.source.controller.ResourceEvent;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;

import java.util.Objects;

@ApplicationScoped
public class ResourceEventSource extends AbstractEventSource implements ResourceEventHandler<HasMetadata> {

    private static Logger log = Logger.getLogger(ResourceEventSource.class);

    @Override
    public void onAdd(HasMetadata resource) {
        log.debugf("Add event received for %s %s/%s", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
        handleEvent(resource, ResourceAction.UPDATED);
    }

    @Override
    public void onUpdate(HasMetadata oldResource, HasMetadata newResource) {
        log.debugf("Update event received for %s %s/%s", oldResource.getKind(), oldResource.getMetadata().getNamespace(), oldResource.getMetadata().getName());
        if (!oldResource.getMetadata().getOwnerReferences().isEmpty() && (newResource.getMetadata().getOwnerReferences().isEmpty() ||
                !Objects.equals(oldResource.getMetadata().getOwnerReferences().get(0).getUid(), newResource.getMetadata().getOwnerReferences().get(0).getUid()))) {
            handleEvent(oldResource, ResourceAction.UPDATED);
        }
        handleEvent(newResource, ResourceAction.UPDATED);
    }

    @Override
    public void onDelete(HasMetadata resource, boolean deletedFinalStateUnknown) {
        log.debugf("Delete event received for %s %s/%s with deletedFinalStateUnknown %s", resource.getKind(),
                resource.getMetadata().getNamespace(), resource.getMetadata().getName(), deletedFinalStateUnknown);
        handleEvent(resource, ResourceAction.UPDATED);
    }

    protected void handleEvent(HasMetadata resource, ResourceAction action) {
        // the operator may not have inited yet
        if (getEventHandler() != null) {
            if(resource.getMetadata().getOwnerReferences().isEmpty()) {
                log.warnf("%s %s/%s does not have OwnerReference", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
            } else {
                getEventHandler().handleEvent(new ResourceEvent(action, ResourceID.fromFirstOwnerReference(resource).get()));
            }
        }
    }

    public void handleEvent(CustomResource resource) {
        if (getEventHandler() != null) {
            getEventHandler().handleEvent(new ResourceEvent(ResourceAction.UPDATED, ResourceID.fromResource(resource)));
        }
    }
}
