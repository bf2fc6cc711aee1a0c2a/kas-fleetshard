package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;

import java.util.List;

/**
 * Defines the current status with related conditions of a ManagedKafka instance
 */
@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class ManagedKafkaStatus {

    private List<ManagedKafkaCondition> conditions;
    private ManagedKafkaCapacity capacity;
    private Versions versions;

    public List<ManagedKafkaCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<ManagedKafkaCondition> conditions) {
        this.conditions = conditions;
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public ManagedKafkaCapacity getCapacity() {
        return capacity;
    }

    public void setCapacity(ManagedKafkaCapacity capacity) {
        this.capacity = capacity;
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public Versions getVersions() {
        return versions;
    }

    public void setVersions(Versions versions) {
        this.versions = versions;
    }
}
