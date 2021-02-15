package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ServiceEventSource extends AbstractEventSource implements ResourceEventHandler<Service> {

    private static final Logger log = LoggerFactory.getLogger(ServiceEventSource.class);

    @Override
    public void onAdd(Service service) {
        log.info("Add event received for Service {}/{}", service.getMetadata().getNamespace(), service.getMetadata().getName());
        handleEvent(service);
    }

    @Override
    public void onUpdate(Service oldService, Service newService) {
        log.info("Update event received for Service {}/{}", oldService.getMetadata().getNamespace(), oldService.getMetadata().getName());
        handleEvent(newService);
    }

    @Override
    public void onDelete(Service service, boolean deletedFinalStateUnknown) {
        log.info("Delete event received for Service {}/{}", service.getMetadata().getNamespace(), service.getMetadata().getName());
        handleEvent(service);
    }

    private void handleEvent(Service service) {
        eventHandler.handleEvent(new ServiceEvent(service, this));
    }
}
