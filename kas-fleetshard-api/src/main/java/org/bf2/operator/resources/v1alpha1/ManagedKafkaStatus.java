package org.bf2.operator.resources.v1alpha1;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.sundr.builder.annotations.Buildable;

/**
 * Defines the current status with related conditions of a ManagedKafka instance
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
public class ManagedKafkaStatus {

    private List<ManagedKafkaCondition> conditions;
    private ManagedKafkaCapacity capacity;
    private Versions versions;
    private String adminServerURI;
    private String updatedTimestamp;

    public List<ManagedKafkaCondition> getConditions() {
        if (conditions == null) {
            conditions = new ArrayList<>();
        }
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

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public String getAdminServerURI() {
        return adminServerURI;
    }

    public void setAdminServerURI(String adminServerURI) {
        this.adminServerURI = adminServerURI;
    }

    public String getUpdatedTimestamp() {
        return updatedTimestamp;
    }

    public void setUpdatedTimestamp(String updatedTimestamp) {
        this.updatedTimestamp = updatedTimestamp;
    }
}
