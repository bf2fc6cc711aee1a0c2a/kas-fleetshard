package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
@Accessors(prefix = "_")
public class NetworkConfiguration {
    private boolean _private;
}
