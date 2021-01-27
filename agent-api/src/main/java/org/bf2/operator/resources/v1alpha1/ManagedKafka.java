package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.dekorate.crd.annotation.Crd;
import io.dekorate.crd.annotation.Status;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("managedkafka.bf2.org")
@Version("v1alpha1")
@Crd(group = "managedkafka.bf2.org", version = "v1alpha1")
public class ManagedKafka extends CustomResource<ManagedKafkaSpec, ManagedKafkaStatus> implements Namespaced {

    private ManagedKafkaSpec spec;
    @Status
    private ManagedKafkaStatus status;

    @Override
    public ManagedKafkaSpec getSpec() {
        return spec;
    }

    @Override
    public void setSpec(ManagedKafkaSpec spec) {
        this.spec = spec;
    }

    @Override
    public ManagedKafkaStatus getStatus() {
        return status;
    }

    @Override
    public void setStatus(ManagedKafkaStatus status) {
        this.status = status;
    }

    @JsonIgnore
    public String getKafkaClusterId() {
        return this.getMetadata().getAnnotations().get("kas.redhat.com/kafka-id");
    }
}
