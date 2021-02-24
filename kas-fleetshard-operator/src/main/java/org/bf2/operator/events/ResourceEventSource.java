package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.openshift.api.model.Route;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import io.javaoperatorsdk.operator.processing.event.Event;
import io.strimzi.api.kafka.model.Kafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;

public class ResourceEventSource<T extends HasMetadata> extends AbstractEventSource implements ResourceEventHandler<T> {

    private static final Logger log = LoggerFactory.getLogger(ResourceEventSource.class);

    @Override
    public void onAdd(T resource) {
        log.info("Add event received for {} {}/{}", resource.getClass().getName(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
        handleEvent(resource);
    }

    @Override
    public void onUpdate(T oldResource, T newResource) {
        log.info("Update event received for {} {}/{}", oldResource.getClass().getName(), oldResource.getMetadata().getNamespace(), oldResource.getMetadata().getName());
        handleEvent(newResource);
    }

    @Override
    public void onDelete(T resource, boolean deletedFinalStateUnknown) {
        log.info("Delete event received for {} {}/{}", resource.getClass().getName(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
        handleEvent(resource);
    }

    private void handleEvent(T resource) {
        Event event;
        if (resource instanceof ConfigMap) {
            event = new ResourceEvent.ConfigMapEvent((ConfigMap) resource, (ConfigMapEventSource) this);
        } else if (resource instanceof Deployment) {
            event = new ResourceEvent.DeploymentEvent((Deployment) resource, (DeploymentEventSource) this);
        } else if (resource instanceof Kafka) {
            event = new ResourceEvent.KafkaEvent((Kafka) resource, (KafkaEventSource) this);
        } else if (resource instanceof Route) {
            event = new ResourceEvent.RouteEvent((Route) resource, (RouteEventSource) this);
        } else if (resource instanceof Service) {
            event = new ResourceEvent.ServiceEvent((Service) resource, (ServiceEventSource) this);
        } else {
            throw new IllegalArgumentException("No matching resource event for type " + resource.getClass().getName());
        }
        eventHandler.handleEvent(event);
    }

    @ApplicationScoped
    public static class ConfigMapEventSource extends ResourceEventSource<ConfigMap> {

    }

    @ApplicationScoped
    public static class DeploymentEventSource extends ResourceEventSource<Deployment> {

    }

    @ApplicationScoped
    public static class KafkaEventSource extends ResourceEventSource<Kafka> {

    }

    @ApplicationScoped
    public static class RouteEventSource extends ResourceEventSource<Route> {

    }

    @ApplicationScoped
    public static class ServiceEventSource extends ResourceEventSource<Service> {

    }
}
