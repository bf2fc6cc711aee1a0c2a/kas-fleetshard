package org.bf2.operator.sync;

import org.bf2.common.AbstractCustomResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;

/**
 * Represents a wrapper around a Kubernetes client for handling operations on a ManagedKafka custom resource
 */
@ApplicationScoped
public class ManagedKafkaResourceClient extends AbstractCustomResourceClient<ManagedKafka, ManagedKafkaList> {

    @PostConstruct
    public void test() {
        System.out.println("Hey");
    }

    @Override
    protected Class<ManagedKafka> getCustomResourceClass() {
        return ManagedKafka.class;
    }

    @Override
    protected Class<ManagedKafkaList> getCustomResourceListClass() {
        return ManagedKafkaList.class;
    }
}
