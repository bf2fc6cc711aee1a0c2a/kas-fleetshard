package org.bf2.operator.resources.v1alpha1;

import java.util.Collection;

import io.fabric8.kubernetes.client.CustomResourceList;

public class ManagedKafkaList extends CustomResourceList<ManagedKafka> {
    private static final long serialVersionUID = 1398496705273012127L;

    public ManagedKafkaList() {

    }

    public ManagedKafkaList(Collection<ManagedKafka> managedKafkas) {
        this.getItems().addAll(managedKafkas);
    }
}
