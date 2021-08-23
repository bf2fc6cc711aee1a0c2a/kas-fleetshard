package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.ToString;

import java.util.List;

/**
 * Defines the current status with related conditions of a ManagedKafka instance
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ManagedKafkaStatus {

    private List<ManagedKafkaCondition> conditions;
    private List<ManagedKafkaRoute> routes;
    private ManagedKafkaCapacity capacity;
    private Versions versions;
    private String adminServerURI;
    private String updatedTimestamp;
    private List<ServiceAccount> serviceAccounts;

    public List<ManagedKafkaCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<ManagedKafkaCondition> conditions) {
        this.conditions = conditions;
    }

    public List<ManagedKafkaRoute> getRoutes() {
        return routes;
    }

    public void setRoutes(List<ManagedKafkaRoute> routes) {
        this.routes = routes;
    }

    public ManagedKafkaCapacity getCapacity() {
        return capacity;
    }

    public void setCapacity(ManagedKafkaCapacity capacity) {
        this.capacity = capacity;
    }

    public Versions getVersions() {
        return versions;
    }

    public void setVersions(Versions versions) {
        this.versions = versions;
    }

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

    public List<ServiceAccount> getServiceAccounts() {
        return serviceAccounts;
    }

    public void setServiceAccounts(List<ServiceAccount> serviceAccounts) {
        this.serviceAccounts = serviceAccounts;
    }
}
