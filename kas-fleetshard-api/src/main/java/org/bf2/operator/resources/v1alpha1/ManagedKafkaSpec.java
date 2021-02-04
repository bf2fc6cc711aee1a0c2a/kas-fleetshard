package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

/**
 * Defines the specification of the ManagedKafka instance
 */
@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class ManagedKafkaSpec {

    private ManagedKafkaCapacity capacity;
    private ManagedKafkaAuthenticationOAuth oauth;
    private ManagedKafkaEndpoint endpoint;
    private Versions versions;

    public ManagedKafkaCapacity getCapacity() {
        return capacity;
    }

    public void setCapacity(ManagedKafkaCapacity capacity) {
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
}
