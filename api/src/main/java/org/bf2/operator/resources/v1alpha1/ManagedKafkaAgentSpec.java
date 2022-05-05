package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
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
@JsonInclude(Include.NON_NULL)
public class ManagedKafkaAgentSpec {
    @NotNull
    ObservabilityConfiguration observability;
    Profile standard;
    Profile developer;

    public ObservabilityConfiguration getObservability() {
        return observability;
    }

    public void setObservability(ObservabilityConfiguration observability) {
        this.observability = observability;
    }

    public Profile getDeveloper() {
        return developer;
    }

    public Profile getStandard() {
        return standard;
    }

    public void setDeveloper(Profile developer) {
        this.developer = developer;
    }

    public void setStandard(Profile standard) {
        this.standard = standard;
    }

}
