package org.bf2.operator.clients;

import javax.enterprise.context.ApplicationScoped;

import org.bf2.common.AbstractCustomResourceClient;

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
}
