package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.Service;
import io.javaoperatorsdk.operator.processing.event.AbstractEvent;

public class ServiceEvent extends AbstractEvent {

    private Service service;

    public ServiceEvent(Service service, ServiceEventSource serviceEventSource) {
        super(service.getMetadata().getOwnerReferences().get(0).getUid(), serviceEventSource);
        this.service = service;
    }

    public Service getService() {
        return service;
    }
}
