package org.bf2.operator.resources.v1alpha1;

import java.util.LinkedHashMap;
import java.util.Map;

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

    private static final String ID_ANNOTATION_KEY = "kas.redhat.com/kafka-id";

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

    @JsonIgnore
    public String getKafkaClusterId() {
        Map<String, String> annotations = this.getMetadata().getAnnotations();
        if (annotations != null) {
            return annotations.get(ID_ANNOTATION_KEY);
        }
        return null;
    }

    public void setKafkaClusterId(String id) {
        Map<String, String> annotations = this.getMetadata().getAnnotations();
        if (annotations == null) {
            annotations = new LinkedHashMap<>();
            this.getMetadata().setAnnotations(annotations);
        }
        annotations.put(ID_ANNOTATION_KEY, id);
    }
}
