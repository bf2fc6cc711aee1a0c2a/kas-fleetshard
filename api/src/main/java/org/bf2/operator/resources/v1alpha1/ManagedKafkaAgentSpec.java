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
    String[] allowedStrimziVersions;
    ObservabilityConfiguration observability;

    public String[] getAllowedStrimziVersions() {
        return allowedStrimziVersions;
    }

    public void setAllowedStrimziVersions(String[] allowedStrimziVersions) {
        this.allowedStrimziVersions = allowedStrimziVersions;
    }

    public ObservabilityConfiguration getObservability() {
        return observability;
    }

    public void setObservability(ObservabilityConfiguration observability) {
        this.observability = observability;
    }
}
