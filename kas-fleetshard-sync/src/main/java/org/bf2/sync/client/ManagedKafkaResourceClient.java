package org.bf2.sync.client;

import java.util.List;
import java.util.function.UnaryOperator;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;

/**
 * Represents a wrapper around a Kubernetes client for handling operations on a
 * Managed Kafka custom resource
 *
 * TODO this should eventually use the common base class
 */
@ApplicationScoped
public class ManagedKafkaResourceClient {

    @Inject
    KubernetesClient kubernetesClient;

    private MixedOperation<ManagedKafka, ManagedKafkaList, Resource<ManagedKafka>> kafkaResourceClient;

    @PostConstruct
    void onStart() {
        kafkaResourceClient = kubernetesClient.customResources(ManagedKafka.class, ManagedKafkaList.class);
    }

    public void delete(String namespace, String name) {
        kafkaResourceClient.inNamespace(namespace).withName(name).delete();
    }

    public ManagedKafka getByName(String namespace, String name) {
        return kafkaResourceClient.inNamespace(namespace).withName(name).get();
    }

    public ManagedKafka create(ManagedKafka kafka) {
        return kafkaResourceClient.inNamespace(kafka.getMetadata().getNamespace()).createOrReplace(kafka);
    }

    public ManagedKafka edit(String namespace, String name, UnaryOperator<ManagedKafka> function) {
        return kafkaResourceClient.inNamespace(namespace).withName(name).edit(function);
    }

    public List<ManagedKafka> list() {
        return kafkaResourceClient.inAnyNamespace().list().getItems();
    }

}
