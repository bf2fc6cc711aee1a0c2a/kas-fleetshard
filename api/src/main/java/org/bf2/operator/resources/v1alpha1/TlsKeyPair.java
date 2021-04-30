package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Represents a TLS keys pair, both public (signed certificate) and private
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false)
@ToString
@EqualsAndHashCode
public class TlsKeyPair {

    private String cert;
    private String key;

    public String getCert() {
        return cert;
    }

    public void setCert(String cert) {
        this.cert = cert;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
