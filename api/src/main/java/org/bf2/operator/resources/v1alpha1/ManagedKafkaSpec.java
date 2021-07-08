package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines the specification of the ManagedKafka instance
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
public class ManagedKafkaSpec {

    private ManagedKafkaCapacity capacity = new ManagedKafkaCapacity();
    private ManagedKafkaAuthenticationOAuth oauth;
    @NotNull
    private ManagedKafkaEndpoint endpoint;
    @NotNull
    private Versions versions;
    private boolean deleted;
    @NotNull
    private List<@NotNull String> owners = new ArrayList<>();

    /**
     * Never null
     */
    public ManagedKafkaCapacity getCapacity() {
        return capacity;
    }

    /**
     * A null value will be treated as empty instead
     */
    public void setCapacity(ManagedKafkaCapacity capacity) {
        if (capacity == null) {
            capacity = new ManagedKafkaCapacity();
        }
        this.capacity = capacity;
    }

    public ManagedKafkaAuthenticationOAuth getOauth() {
        return oauth;
    }

    public void setOauth(ManagedKafkaAuthenticationOAuth oauth) {
        this.oauth = oauth;
    }

    public ManagedKafkaEndpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(ManagedKafkaEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    public Versions getVersions() {
        return versions;
    }

    public void setVersions(Versions versions) {
        this.versions = versions;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public List<String> getOwners() {
        return owners;
    }

    public void setOwners(List<String> owners) {
        this.owners = owners;
    }
}
