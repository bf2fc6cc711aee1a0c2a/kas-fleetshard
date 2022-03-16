package org.bf2.common;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.openshift.api.model.Route;
import io.quarkus.runtime.Startup;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.events.ResourceEventSource;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

@Startup
@ApplicationScoped
public class InformerManager {

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ResourceEventSource eventSource;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    @Inject
    OpenShiftSupport openShiftSupport;

    private volatile ResourceInformer<Kafka> kafkaInformer;
    private ResourceInformer<Deployment> deploymentInformer;
    private ResourceInformer<Service> serviceInformer;
    private ResourceInformer<ConfigMap> configMapInformer;
    private ResourceInformer<Secret> secretInformer;
    private ResourceInformer<Route> routeInformer;
    private ResourceInformer<PersistentVolumeClaim> pvcInformer;

    boolean isOpenShift() {
        return openShiftSupport.isOpenShift(kubernetesClient);
    }

    @PostConstruct
    protected void onStart() {
        deploymentInformer = resourceInformerFactory.create(Deployment.class, filter(kubernetesClient.apps().deployments()), eventSource);

        serviceInformer = resourceInformerFactory.create(Service.class, filterManagedByFleetshardOrStrimzi(kubernetesClient.services()), eventSource);

        configMapInformer = resourceInformerFactory.create(ConfigMap.class, filter(kubernetesClient.configMaps()), eventSource);

        secretInformer = resourceInformerFactory.create(Secret.class, filter(kubernetesClient.secrets()), eventSource);

        // pvcs have an owner reference set to the kafka, not managedkakfa, so we need some lookup logic in the handleEvent
        pvcInformer = resourceInformerFactory.create(PersistentVolumeClaim.class,
                kubernetesClient.persistentVolumeClaims().inAnyNamespace().withLabel("app.kubernetes.io/name", "kafka"),
                new ResourceEventSource() {

                    @Override
                    protected void handleEvent(HasMetadata resource, Action action) {
                        if (kafkaInformer != null) {
                            // TODO: could index by uid, or use namespace
                            String name =
                                    OperandUtils.getOrDefault(resource.getMetadata().getLabels(), "strimzi.io/cluster", null);
                            if (name != null) {
                                Kafka kafka = kafkaInformer
                                        .getByKey(Cache.namespaceKeyFunc(resource.getMetadata().getNamespace(), name));
                                if (kafka != null) {
                                    handleEvent(kafka, Action.MODIFIED);
                                }
                            }
                        }
                    }
                });

        if (isOpenShift()) {
            routeInformer = resourceInformerFactory.create(Route.class, filterManagedByFleetshardOrStrimzi(openShiftSupport.adapt(kubernetesClient).routes()), eventSource);
        }
    }

    static <T extends HasMetadata> FilterWatchListDeletable<T, ? extends KubernetesResourceList<T>> filter(
            MixedOperation<T, ? extends KubernetesResourceList<T>, ?> mixedOperation) {
        return mixedOperation.inAnyNamespace().withLabels(OperandUtils.getDefaultLabels());
    }

    static <T extends HasMetadata> FilterWatchListDeletable<T, ? extends KubernetesResourceList<T>> filterManagedByFleetshardOrStrimzi(
            MixedOperation<T, ? extends KubernetesResourceList<T>, ?> mixedOperation) {
        return mixedOperation.inAnyNamespace().withLabelIn(OperandUtils.MANAGED_BY_LABEL, OperandUtils.FLEETSHARD_OPERATOR_NAME, OperandUtils.STRIMZI_OPERATOR_NAME);
    }

    public Kafka getLocalKafka(String namespace, String name) {
        return kafkaInformer != null ? kafkaInformer.getByKey(Cache.namespaceKeyFunc(namespace, name)) : null;
    }

    public Deployment getLocalDeployment(String namespace, String name) {
        return deploymentInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Service getLocalService(String namespace, String name) {
        return serviceInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public ConfigMap getLocalConfigMap(String namespace, String name) {
        return configMapInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Secret getLocalSecret(String namespace, String name) {
        return secretInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Route getLocalRoute(String namespace, String name) {
        if (isOpenShift()) {
            return routeInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
        } else {
            log.warn("Not running on OpenShift cluster, Routes are not available");
            return null;
        }
    }

    protected Stream<Route> getRoutesInNamespace(String namespace) {
        if (isOpenShift()) {
            return routeInformer.getList().stream()
                    .filter(r -> r.getMetadata().getNamespace().equals(namespace));
        } else {
            log.warn("Not running on OpenShift cluster, Routes are not available");
            return Stream.empty();
        }
    }

    /**
     * Create the Kafka informer
     * NOTE: it's called when a Strimzi bundle is installed and Kafka related CRDs are available to be listed/watched
     */
    public void createKafkaInformer() {
        if (kafkaInformer == null) {
            kafkaInformer = resourceInformerFactory.create(Kafka.class, filter(kubernetesClient.resources(Kafka.class, KafkaList.class)), eventSource);
        }
    }

    /**
     * Trigger Kafka CR changes following external context changes.
     */
    public void resyncKafkas() {
        if (kafkaInformer != null) {
            List<Kafka> kafkaList = kafkaInformer.getList();
            log.debugf("Kafka instances to be resynced: %d", kafkaList.size());
            kafkaList.forEach(k -> {
                this.eventSource.onUpdate(k, k);
            });
        }
    }

    public List<Kafka> getKafkas() {
        if (kafkaInformer != null) {
            return kafkaInformer.getList();
        }
        return Collections.emptyList();
    }

    public void resyncManagedKafka() {
        resyncResource(ManagedKafka.class);
    }

    public void resyncManagedKafkaAgent() {
        resyncResource(ManagedKafkaAgent.class);
    }

    protected <T extends CustomResource<?, ?>> void resyncResource(Class<T> resourceType) {
        List<T> list = kubernetesClient.resources(resourceType).inAnyNamespace().list().getItems();
        log.debugf("%s instances to be resynced: %d", resourceType.getSimpleName(), list.size());
        list.forEach(resource -> {
            this.eventSource.handleEvent(resource);
        });
    }

    public void resyncManagedKafka(ManagedKafka managedKafka) {
        this.eventSource.handleEvent(managedKafka);
    }

    public List<PersistentVolumeClaim> getPvcsInNamespace(String namespace) {
        return this.pvcInformer.getByNamespace(namespace);
    }
}
