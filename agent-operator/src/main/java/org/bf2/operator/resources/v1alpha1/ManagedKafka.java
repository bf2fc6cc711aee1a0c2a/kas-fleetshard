package org.bf2.operator.resources.v1alpha1;

import io.dekorate.crd.annotation.Status;
import io.fabric8.kubernetes.client.CustomResource;

@io.dekorate.crd.annotation.CustomResource(group = "managedkafka.bf2.org", version = "v1alpha1")
public class ManagedKafka extends CustomResource {

    private ManagedKafkaSpec spec;
    @Status
    private ManagedKafkaStatus status;

    public ManagedKafkaSpec getSpec() {
        return spec;
    }

    public void setSpec(ManagedKafkaSpec spec) {
        this.spec = spec;
    }

    public ManagedKafkaStatus getStatus() {
        return status;
    }

    public void setStatus(ManagedKafkaStatus status) {
        this.status = status;
    }
}
