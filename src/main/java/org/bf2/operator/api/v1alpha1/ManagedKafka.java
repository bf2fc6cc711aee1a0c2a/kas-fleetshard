package org.bf2.operator.api.v1alpha1;

import io.fabric8.kubernetes.client.CustomResource;

@io.dekorate.crd.annotation.CustomResource(group = "org.bf2", version = "v1alpha1")
public class ManagedKafka extends CustomResource {

    private ManagedKafkaSpec spec;

    public ManagedKafkaSpec getSpec() {
        return spec;
    }

    public void setSpec(ManagedKafkaSpec spec) {
        this.spec = spec;
    }
}
