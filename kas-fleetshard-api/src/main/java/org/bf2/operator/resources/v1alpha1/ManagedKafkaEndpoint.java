package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

/**
 * Defines the endpoint related information used for reaching the ManagedKafka instance
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
public class ManagedKafkaEndpoint {

    private String bootstrapAddress;
    private TlsKeyPair tls;

    public String getBootstrapAddress() {
        return bootstrapAddress;
    }

    public void setBootstrapAddress(String bootstrapAddress) {
        this.bootstrapAddress = bootstrapAddress;
    }

    public TlsKeyPair getTls() {
        return tls;
    }

    public void setTls(TlsKeyPair tls) {
        this.tls = tls;
    }
}
