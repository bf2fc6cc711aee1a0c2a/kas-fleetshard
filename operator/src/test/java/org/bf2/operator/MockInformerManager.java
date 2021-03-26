package org.bf2.operator;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.clients.KafkaResourceClient;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.Route;
import io.quarkus.test.Mock;
import io.strimzi.api.kafka.model.Kafka;

/**
 * To bypass the informer async update, we can lookup directly
 * against the (Mock) server
 */
@Mock
@ApplicationScoped
public class MockInformerManager extends InformerManager {

    @Inject
    KafkaResourceClient kafkaClient;

    @Inject
    KubernetesClient kubeClient;

    @Override
    public ConfigMap getLocalConfigMap(String namespace, String name) {
        return kubeClient.configMaps().inNamespace(namespace).withName(name).get();
    }

    @Override
    public Deployment getLocalDeployment(String namespace, String name) {
        return kubeClient.apps().deployments().inNamespace(namespace).withName(name).get();
    }

    @Override
    public Kafka getLocalKafka(String namespace, String name) {
        return kafkaClient.getByName(namespace, name);
    }

    @Override
    public Route getLocalRoute(String namespace, String name) {
        return null;
    }

    @Override
    public Secret getLocalSecret(String namespace, String name) {
        return kubeClient.secrets().inNamespace(namespace).withName(name).get();
    }

    @Override
    public Service getLocalService(String namespace, String name) {
        return kubeClient.services().inNamespace(namespace).withName(name).get();
    }

    @Override
    public boolean isReady() {
        return true;
    }

}
