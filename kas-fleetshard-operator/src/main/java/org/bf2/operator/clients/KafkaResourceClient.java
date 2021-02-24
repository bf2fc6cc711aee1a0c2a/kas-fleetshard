package org.bf2.operator.clients;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;

import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;

/**
 * Represents a wrapper around a Kubernetes client for handling operations on a Kafka custom resource
 */
@ApplicationScoped
public class KafkaResourceClient extends AbstractCustomResourceClient<Kafka, KafkaList> {

    @Override
    protected Class<Kafka> getCustomResourceClass() {
        return Kafka.class;
    }

    @Override
    protected Class<KafkaList> getCustomResourceListClass() {
        return KafkaList.class;
    }

    @Override
    protected MixedOperation<Kafka, KafkaList, Resource<Kafka>> getResourceClient() {
        // creating a CRD context which is based on v1beta1 (returned by the Crds.kafka() method)
        var crdContext = CustomResourceDefinitionContext.fromCrd(Crds.kafka());
        return kubernetesClient.customResources(crdContext, getCustomResourceClass(), getCustomResourceListClass());
    }
}
