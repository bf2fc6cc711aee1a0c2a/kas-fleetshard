package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.javaoperatorsdk.operator.processing.event.AbstractEvent;

public class ResourceEvent<T extends HasMetadata> extends AbstractEvent {

    private T resource;

    public ResourceEvent(T resource, ResourceEventSource resourceEventSource) {
        super(resource.getMetadata().getOwnerReferences().get(0).getUid(), resourceEventSource);
        this.resource = resource;
    }

    public T getResource() {
        return resource;
    }

    @Override
    public String toString() {
        return "{ "
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
