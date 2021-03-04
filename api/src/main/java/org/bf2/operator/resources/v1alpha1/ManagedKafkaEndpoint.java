package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Defines the endpoint related information used for reaching the ManagedKafka instance
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
public class ManagedKafkaEndpoint {

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
