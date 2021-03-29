package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.openshift.api.model.Route;
import io.javaoperatorsdk.operator.processing.event.AbstractEvent;
import io.strimzi.api.kafka.model.Kafka;

public class ResourceEvent<T extends HasMetadata> extends AbstractEvent {

    private T resource;

    public ResourceEvent(T resource, ResourceEventSource<T> resourceEventSource) {
        super(resource.getMetadata().getOwnerReferences().get(0).getUid(), resourceEventSource);
        this.resource = resource;
    }

    public T getResource() {
        return resource;
    }

    public static class ConfigMapEvent extends ResourceEvent<ConfigMap> {

        public ConfigMapEvent(ConfigMap resource, ResourceEventSource<ConfigMap> resourceEventSource) {
            super(resource, resourceEventSource);
        }
    }

    public static class DeploymentEvent extends ResourceEvent<Deployment> {

        public DeploymentEvent(Deployment resource, ResourceEventSource<Deployment> resourceEventSource) {
            super(resource, resourceEventSource);
        }

        @Override
        public boolean shouldUpdateStatus() {
            return true;
        }
    }

    public static class KafkaEvent extends ResourceEvent<Kafka> {

        public KafkaEvent(Kafka resource, ResourceEventSource<Kafka> resourceEventSource) {
            super(resource, resourceEventSource);
        }

        @Override
        public boolean shouldUpdateStatus() {
            return true;
        }
    }

    public static class RouteEvent extends ResourceEvent<Route> {

        public RouteEvent(Route resource, ResourceEventSource<Route> resourceEventSource) {
            super(resource, resourceEventSource);
        }
    }

    public static class ServiceEvent extends ResourceEvent<Service> {

        public ServiceEvent(Service resource, ResourceEventSource<Service> resourceEventSource) {
            super(resource, resourceEventSource);
        }
    }

    public static class SecretEvent extends ResourceEvent<Secret> {

        public SecretEvent(Secret resource, ResourceEventSource<Secret> resourceEventSource) {
            super(resource, resourceEventSource);
        }
    }

    public boolean shouldUpdateStatus() {
        return false;
    }
}
