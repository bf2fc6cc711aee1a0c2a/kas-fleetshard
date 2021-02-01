package org.bf2.operator.clients;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.runtime.StartupEvent;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

/**
 * Represents a wrapper around a Kubernetes client for handling operations on a Kafka custom resource
 */
@ApplicationScoped
public class KafkaResourceClient {

    @Inject
    private KubernetesClient kubernetesClient;

    private MixedOperation<Kafka, KafkaList, Resource<Kafka>> kafkaResourceClient;

    void onStart(@Observes StartupEvent ev) {
        kafkaResourceClient = kubernetesClient.customResources(Kafka.class, KafkaList.class);
    }

    public void delete(String namespace, String name) {
        kafkaResourceClient
                .inNamespace(namespace)
                .withName(name)
                .delete();
    }

    public Kafka getByName(String namespace, String name) {
        return kafkaResourceClient
                .inNamespace(namespace)
                .withName(name).get();
    }

    public Kafka create(Kafka kafka) {
        return kafkaResourceClient.inNamespace(kafka.getMetadata().getNamespace()).createOrReplace(kafka);
    }
}
