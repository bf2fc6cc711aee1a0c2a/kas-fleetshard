package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.api.model.SecretKeySelector;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Represents a TLS keys pair, both public (signed certificate) and private
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TlsKeyPair {

    //@NotNull
    private String cert;
    //@NotNull
    private String key;
    private SecretKeySelector certRef;
    private SecretKeySelector keyRef;

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

    public SecretKeySelector getCertRef() {
        return certRef;
    }

    public void setCertRef(SecretKeySelector certRef) {
        this.certRef = certRef;
    }

    public SecretKeySelector getKeyRef() {
        return keyRef;
    }

    public void setKeyRef(SecretKeySelector keyRef) {
        this.keyRef = keyRef;
    }
}
