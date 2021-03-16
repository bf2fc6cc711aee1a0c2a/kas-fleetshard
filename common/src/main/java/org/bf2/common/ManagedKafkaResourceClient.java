package org.bf2.common;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;

import javax.enterprise.context.ApplicationScoped;

/**
 * Represents a wrapper around a Kubernetes client for handling operations on a ManagedKafka custom resource
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
