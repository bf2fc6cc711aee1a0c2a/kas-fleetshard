package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.openshift.api.model.Route;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import io.strimzi.api.kafka.model.Kafka;
import org.jboss.logging.Logger;

import java.util.Objects;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

public abstract class ResourceEventSource<T extends HasMetadata> extends AbstractEventSource implements ResourceEventHandler<T> {

    @Inject
    Logger log;

    @Override
    public void onAdd(T resource) {
        log.debugf("Add event received for %s %s/%s", resource.getClass().getName(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
        handleEvent(resource);
    }

    @Override
    public void onUpdate(T oldResource, T newResource) {
        if (Objects.equals(oldResource.getMetadata().getResourceVersion(), newResource.getMetadata().getResourceVersion())) {
            return; // no need to handle an event where nothing has changed
        }
        log.debugf("Update event received for %s %s/%s", oldResource.getClass().getName(), oldResource.getMetadata().getNamespace(), oldResource.getMetadata().getName());
        handleEvent(newResource);
    }

    @Override
    public void onDelete(T resource, boolean deletedFinalStateUnknown) {
        log.debugf("Delete event received for %s %s/%s", resource.getClass().getName(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
        handleEvent(resource);
    }

    protected abstract void handleEvent(T resource);

    @ApplicationScoped
    public static class ConfigMapEventSource extends ResourceEventSource<ConfigMap> {

        @Override
        protected void handleEvent(ConfigMap resource) {
            eventHandler.handleEvent(new ResourceEvent.ConfigMapEvent(resource, this));
        }
    }

    @ApplicationScoped
    public static class DeploymentEventSource extends ResourceEventSource<Deployment> {

        @Override
        protected void handleEvent(Deployment resource) {
            eventHandler.handleEvent(new ResourceEvent.DeploymentEvent(resource, this));
        }
    }

    @ApplicationScoped
    public static class KafkaEventSource extends ResourceEventSource<Kafka> {

        @Override
        protected void handleEvent(Kafka resource) {
            eventHandler.handleEvent(new ResourceEvent.KafkaEvent(resource, this));
        }
    }

    @ApplicationScoped
    public static class RouteEventSource extends ResourceEventSource<Route> {

        @Override
        protected void handleEvent(Route resource) {
            eventHandler.handleEvent(new ResourceEvent.RouteEvent(resource, this));
        }
    }

    @ApplicationScoped
    public static class ServiceEventSource extends ResourceEventSource<Service> {

        @Override
        protected void handleEvent(Service resource) {
            eventHandler.handleEvent(new ResourceEvent.ServiceEvent(resource, this));
        }
    }

    @ApplicationScoped
    public static class SecretEventSource extends ResourceEventSource<Secret> {

        @Override
        protected void handleEvent(Secret resource) {
            eventHandler.handleEvent(new ResourceEvent.SecretEvent(resource, this));
        }
    }
}
