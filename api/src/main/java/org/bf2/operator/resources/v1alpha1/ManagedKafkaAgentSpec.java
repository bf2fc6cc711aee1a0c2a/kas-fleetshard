package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.validation.constraints.NotNull;

@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
public class ManagedKafkaAgentSpec {
    @NotNull
    ObservabilityConfiguration observability;

    public ObservabilityConfiguration getObservability() {
        return observability;
    }

    public void setObservability(ObservabilityConfiguration observability) {
        this.observability = observability;
    }
}
