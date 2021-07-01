package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.ToString;

import javax.validation.constraints.NotNull;

/**
 * Define the status for installed Strimzi versions on the Kubernetes cluster
 * and if they are ready or not
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StrimziVersionStatus {

    @NotNull
    private String version;
    @NotNull
    private boolean ready;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }
}
