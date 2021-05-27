package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.Watcher;
import io.javaoperatorsdk.operator.processing.event.AbstractEvent;

public class ResourceEvent<T extends HasMetadata> extends AbstractEvent {

    private T resource;
    private Watcher.Action action;

    public ResourceEvent(T resource, ResourceEventSource resourceEventSource, Watcher.Action action) {
        super(resource.getMetadata().getOwnerReferences().get(0).getUid(), resourceEventSource);
        this.resource = resource;
        this.action = action;
    }

    public T getResource() {
        return resource;
    }

    public Watcher.Action getAction() {
        return action;
    }

    @Override
    public String toString() {
        return "{ "
                + action
                + " "
                + resource.getKind()
                + " "
                + resource.getMetadata().getNamespace() + "/" + resource.getMetadata().getName()
                + " relatedCustomResourceUid="
                + getRelatedCustomResourceUid()
                + " , resourceVersion="
                + resource.getMetadata().getResourceVersion()
                + " }";
    }

}
