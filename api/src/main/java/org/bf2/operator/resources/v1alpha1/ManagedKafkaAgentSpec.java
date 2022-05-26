package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.validation.constraints.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

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
    Map<String, Profile> capacity = new LinkedHashMap<>();

    public ObservabilityConfiguration getObservability() {
        return observability;
    }

    public void setObservability(ObservabilityConfiguration observability) {
        this.observability = observability;
    }

    public Map<String, Profile> getCapacity() {
        return capacity;
    }

    public void setCapacity(Map<String, Profile> capacity) {
        this.capacity = capacity;
    }

}
