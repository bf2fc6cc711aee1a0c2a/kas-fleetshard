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
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.ResourceInformer;
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

    private ResourceInformer<Kafka> kafkaInformer;
    private ResourceInformer<Deployment> deploymentInformer;
    private ResourceInformer<Service> serviceInformer;
    private ResourceInformer<ConfigMap> configMapInformer;
    private ResourceInformer<Secret> secretInformer;
    private ResourceInformer<Route> routeInformer;


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
        kafkaInformer =  new ResourceInformer<>(
                sharedInformerFactory.sharedIndexInformerFor(Kafka.class, KafkaList.class, operationContext, 0),
                kafkaEventSource);

        deploymentInformer = new ResourceInformer<>(
                sharedInformerFactory.sharedIndexInformerFor(Deployment.class, DeploymentList.class, operationContext, 0),
                deploymentEventSource);

        serviceInformer = new ResourceInformer<>(
                sharedInformerFactory.sharedIndexInformerFor(Service.class, ServiceList.class, operationContext, 0),
                serviceEventSource);

        configMapInformer = new ResourceInformer<>(
                sharedInformerFactory.sharedIndexInformerFor(ConfigMap.class, ConfigMapList.class, operationContext, 0),
                configMapEventSource);

        secretInformer = new ResourceInformer<>(
                sharedInformerFactory.sharedIndexInformerFor(Secret.class, SecretList.class, operationContext, 0),
                secretEventSource);

        if (isOpenShift()) {
            routeInformer = new ResourceInformer<>(
                    sharedInformerFactory.sharedIndexInformerFor(Route.class, RouteList.class, operationContext, 0),
                    routeEventSource);
        }

        sharedInformerFactory.startAllRegisteredInformers();
    }

    void onStop(@Observes ShutdownEvent ev) {
        sharedInformerFactory.stopAllRegisteredInformers();
    }

    public Kafka getLocalKafka(String namespace, String name) {
        return kafkaInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
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

    public boolean isReady() {
        return kafkaInformer.isReady()
                && deploymentInformer.isReady()
                && serviceInformer.isReady()
                && configMapInformer.isReady()
                && secretInformer.isReady()
                && (!isOpenShift() || routeInformer.isReady());
    }
}
