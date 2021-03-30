package org.bf2.operator;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.events.ResourceEventSource;
import org.bf2.operator.operands.OperandUtils;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

@ApplicationScoped
public class InformerManager {

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ResourceEventSource.KafkaEventSource kafkaEventSource;
    @Inject
    ResourceEventSource.DeploymentEventSource deploymentEventSource;
    @Inject
    ResourceEventSource.ServiceEventSource serviceEventSource;
    @Inject
    ResourceEventSource.ConfigMapEventSource configMapEventSource;
    @Inject
    ResourceEventSource.SecretEventSource secretEventSource;
    @Inject
    ResourceEventSource.RouteEventSource routeEventSource;

    private SharedInformerFactory sharedInformerFactory;

    private SharedIndexInformer<Kafka> kafkaSharedIndexInformer;
    private SharedIndexInformer<Deployment> deploymentSharedIndexInformer;
    private SharedIndexInformer<Service> serviceSharedIndexInformer;
    private SharedIndexInformer<ConfigMap> configMapSharedIndexInformer;
    private SharedIndexInformer<Secret> secretSharedIndexInformer;
    private SharedIndexInformer<Route> routeSharedIndexInformer;

    boolean isOpenShift() {
        return kubernetesClient.isAdaptable(OpenShiftClient.class);
    }

    void onStart(@Observes StartupEvent ev) {
        sharedInformerFactory = kubernetesClient.informers();

        OperationContext operationContext =
                new OperationContext()
                        .withLabels(OperandUtils.getDefaultLabels())
                        .withIsNamespaceConfiguredFromGlobalConfig(true);

        // TODO: should we make the resync time configurable?

        kafkaSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Kafka.class, KafkaList.class, operationContext, 60 * 1000L);
        kafkaSharedIndexInformer.addEventHandler(kafkaEventSource);

        deploymentSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Deployment.class, DeploymentList.class, operationContext, 60 * 1000L);
        deploymentSharedIndexInformer.addEventHandler(deploymentEventSource);

        serviceSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Service.class, ServiceList.class, operationContext, 60 * 1000L);
        serviceSharedIndexInformer.addEventHandler(serviceEventSource);

        configMapSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(ConfigMap.class, ConfigMapList.class, operationContext, 60 * 1000L);
        configMapSharedIndexInformer.addEventHandler(configMapEventSource);

        secretSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Secret.class, SecretList.class, operationContext, 60 * 1000L);
        secretSharedIndexInformer.addEventHandler(secretEventSource);

        if (isOpenShift()) {
            routeSharedIndexInformer =
                    sharedInformerFactory.sharedIndexInformerFor(Route.class, RouteList.class, operationContext, 60 * 1000L);
            routeSharedIndexInformer.addEventHandler(routeEventSource);
        }

        sharedInformerFactory.startAllRegisteredInformers();
    }

    void onStop(@Observes ShutdownEvent ev) {
        sharedInformerFactory.stopAllRegisteredInformers();
    }

    public Kafka getLocalKafka(String namespace, String name) {
        return kafkaSharedIndexInformer.getIndexer().getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Deployment getLocalDeployment(String namespace, String name) {
        return deploymentSharedIndexInformer.getIndexer().getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Service getLocalService(String namespace, String name) {
        return serviceSharedIndexInformer.getIndexer().getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public ConfigMap getLocalConfigMap(String namespace, String name) {
        return configMapSharedIndexInformer.getIndexer().getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Secret getLocalSecret(String namespace, String name) {
        return secretSharedIndexInformer.getIndexer().getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Route getLocalRoute(String namespace, String name) {
        if (isOpenShift()) {
            return routeSharedIndexInformer.getIndexer().getByKey(Cache.namespaceKeyFunc(namespace, name));
        } else {
            log.warn("Not running on OpenShift cluster, Routes are not available");
            return null;
        }
    }

    static boolean hasLength(String value) {
        return value != null && !value.isEmpty();
    }

    public boolean isReady() {
        return hasLength(kafkaSharedIndexInformer.lastSyncResourceVersion())
                && hasLength(deploymentSharedIndexInformer.lastSyncResourceVersion())
                && hasLength(serviceSharedIndexInformer.lastSyncResourceVersion())
                && hasLength(configMapSharedIndexInformer.lastSyncResourceVersion())
                && hasLength(secretSharedIndexInformer.lastSyncResourceVersion())
                && (!isOpenShift() || hasLength(routeSharedIndexInformer.lastSyncResourceVersion()));
    }
}
