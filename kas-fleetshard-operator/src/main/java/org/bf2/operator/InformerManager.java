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
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;

import org.bf2.operator.eventhandlers.ObservabilityHandler;
import org.bf2.operator.events.ConfigMapEventSource;
import org.bf2.operator.events.DeploymentEventSource;
import org.bf2.operator.events.KafkaEventSource;
import org.bf2.operator.events.RouteEventSource;
import org.bf2.operator.events.ServiceEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.Collections;

@ApplicationScoped
public class InformerManager {
    public static final String SECRET_NAME = "addon-kas-fleetshard-operator-parameters";

    private static final Logger log = LoggerFactory.getLogger(InformerManager.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    KafkaEventSource kafkaEventSource;
    @Inject
    DeploymentEventSource deploymentEventSource;
    @Inject
    ServiceEventSource serviceEventSource;
    @Inject
    ConfigMapEventSource configMapEventSource;
    @Inject
    RouteEventSource routeEventSource;
    @Inject
    ObservabilityHandler observabilityHandler;

    private SharedInformerFactory sharedInformerFactory;

    private SharedIndexInformer<Kafka> kafkaSharedIndexInformer;
    private SharedIndexInformer<Deployment> deploymentSharedIndexInformer;
    private SharedIndexInformer<Service> serviceSharedIndexInformer;
    private SharedIndexInformer<ConfigMap> configMapSharedIndexInformer;
    private SharedIndexInformer<Route> routeSharedIndexInformer;
    private SharedIndexInformer<Secret> secretSharedIndexInformer;

    void onStart(@Observes StartupEvent ev) {
        sharedInformerFactory = kubernetesClient.informers();

        OperationContext operationContext =
                new OperationContext()
                        .withLabels(Collections.singletonMap("app.kubernetes.io/managed-by", "kas-fleetshard-operator"))
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

        if (kubernetesClient.isAdaptable(OpenShiftClient.class)) {
            routeSharedIndexInformer =
                    sharedInformerFactory.sharedIndexInformerFor(Route.class, RouteList.class, operationContext, 60 * 1000L);
            routeSharedIndexInformer.addEventHandler(routeEventSource);
        }

        // no resync needed, also namespace scoped
        OperationContext secretContext = new OperationContext().withNamespace(this.kubernetesClient.getNamespace())
                .withName(SECRET_NAME);
        secretSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Secret.class, SecretList.class, secretContext, 60 * 1000L);
        secretSharedIndexInformer.addEventHandler(this.observabilityHandler);

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

    public Route getLocalRoute(String namespace, String name) {
        if (kubernetesClient.isAdaptable(OpenShiftClient.class)) {
            return routeSharedIndexInformer.getIndexer().getByKey(Cache.namespaceKeyFunc(namespace, name));
        } else {
            log.warn("Not running on OpenShift cluster, Routes are not available");
            return null;
        }
    }
}
