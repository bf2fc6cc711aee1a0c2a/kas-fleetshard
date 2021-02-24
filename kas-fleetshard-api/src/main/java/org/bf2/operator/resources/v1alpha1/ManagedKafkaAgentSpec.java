package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

import lombok.EqualsAndHashCode;
import lombok.ToString;

@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
public class ManagedKafkaAgentSpec {
    /**
     * Cluster ID given by the control plane
     */
    String clusterId;
    String[] allowedStrimziVersions;

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String[] getAllowedStrimziVersions() {
        return allowedStrimziVersions;
    }

    public void setAllowedStrimziVersions(String[] allowedStrimziVersions) {
        this.allowedStrimziVersions = allowedStrimziVersions;
    }
}
