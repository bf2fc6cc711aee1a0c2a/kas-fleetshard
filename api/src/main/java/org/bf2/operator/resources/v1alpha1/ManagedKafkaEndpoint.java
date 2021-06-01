package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.validation.constraints.NotNull;

/**
 * Defines the endpoint related information used for reaching the ManagedKafka instance
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ManagedKafkaEndpoint {

    @NotNull
    private String bootstrapServerHost;
    private TlsKeyPair tls;

    public String getBootstrapServerHost() {
        return bootstrapServerHost;
    }

    public void setBootstrapServerHost(String bootstrapServerHost) {
        this.bootstrapServerHost = bootstrapServerHost;
    }

    public TlsKeyPair getTls() {
        return tls;
    }

    public void setTls(TlsKeyPair tls) {
        this.tls = tls;
    }
}
