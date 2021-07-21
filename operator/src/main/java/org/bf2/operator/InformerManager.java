package org.bf2.operator;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.runtime.Startup;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.OperandUtils;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.events.ResourceEventSource;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.List;

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

    private volatile ResourceInformer<Kafka> kafkaInformer;
    private ResourceInformer<Deployment> deploymentInformer;
    private ResourceInformer<Service> serviceInformer;
    private ResourceInformer<ConfigMap> configMapInformer;
    private ResourceInformer<Secret> secretInformer;
    private ResourceInformer<Route> routeInformer;


    boolean isOpenShift() {
        return kubernetesClient.isAdaptable(OpenShiftClient.class);
    }

    @PostConstruct
    protected void onStart() {
        deploymentInformer = resourceInformerFactory.create(Deployment.class, filter(kubernetesClient.apps().deployments()), eventSource);

        serviceInformer = resourceInformerFactory.create(Service.class, filter(kubernetesClient.services()), eventSource);

        configMapInformer = resourceInformerFactory.create(ConfigMap.class, filter(kubernetesClient.configMaps()), eventSource);

        secretInformer = resourceInformerFactory.create(Secret.class, filter(kubernetesClient.secrets()), eventSource);

        if (isOpenShift()) {
            routeInformer = resourceInformerFactory.create(Route.class, filter(kubernetesClient.adapt(OpenShiftClient.class).routes()), eventSource);
        }
    }

    static <T extends HasMetadata> FilterWatchListDeletable<T, ? extends KubernetesResourceList<T>> filter(
            MixedOperation<T, ? extends KubernetesResourceList<T>, ?> mixedOperation) {
        return mixedOperation.inAnyNamespace().withLabels(OperandUtils.getDefaultLabels());
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

    /**
     * Create the Kafka informer
     * NOTE: it's called when a Strimzi bundle is installed and Kafka related CRDs are available to be listed/watched
     */
    public synchronized void createKafkaInformer() {
        if (kafkaInformer == null) {
            kafkaInformer = resourceInformerFactory.create(Kafka.class, filter(kubernetesClient.customResources(Kafka.class, KafkaList.class)), eventSource);
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
}
