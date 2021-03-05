package org.bf2.sync;

import javax.enterprise.context.ApplicationScoped;

import org.bf2.common.AbstractCustomResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;

/**
 * Represents a wrapper around a Kubernetes client for handling operations on a
 * Managed Kafka custom resource
 */
@ApplicationScoped
public class ManagedKafkaResourceClient extends AbstractCustomResourceClient<ManagedKafka, ManagedKafkaList> {

    @Override
    protected Class<ManagedKafka> getCustomResourceClass() {
        return ManagedKafka.class;
    }

    @Override
    protected Class<ManagedKafkaList> getCustomResourceListClass() {
        return ManagedKafkaList.class;
    }

}
