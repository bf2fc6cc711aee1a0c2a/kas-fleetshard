package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.informers.cache.Indexer;
import io.fabric8.openshift.api.model.Route;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.IndexerAwareResourceEventHandler;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Objects;

public abstract class ResourceEventSource<T extends HasMetadata> extends AbstractEventSource implements IndexerAwareResourceEventHandler<T> {

    @Inject
    Logger log;

    Indexer<T> indexer;

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
        log.debugf("Delete event received for %s %s/%s with deletedFinalStateUnknown %s", resource.getClass().getName(),
                resource.getMetadata().getNamespace(), resource.getMetadata().getName(), deletedFinalStateUnknown);
        handleEvent(resource);

        // TODO: remove when below issue is resolved and in the quarkus version being used
        // This is workaround for bug in the fabric8 around missed delete event
        // failure to reconcile during resync in DefaultSharedIndexInformer#handleDeltas
        // https://github.com/fabric8io/kubernetes-client/issues/2994
        if (deletedFinalStateUnknown) {
            this.indexer.delete(resource);
        }
    }

    @Override
    public void setIndexer(Indexer<T> indexer) {
        this.indexer = indexer;
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
            // observability secret does not have an owner reference
            if (!resource.getMetadata().getOwnerReferences().isEmpty()) {
                eventHandler.handleEvent(new ResourceEvent.SecretEvent(resource, this));
            }
        }
    }
}
