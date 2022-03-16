package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.api.model.SecretKeySelector;
import io.sundr.builder.annotations.Buildable;
import lombok.ToString;

import javax.validation.constraints.NotNull;

/**
 * Define a service account to be used by a specific Kafka instance component (i.e. canary)
 * to authenticate to Kafka brokers through the authentication service (i.e. Keycloak)
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceAccount {

    public enum ServiceAccountName {
        Canary;

        public static ServiceAccountName forValue(String value) {
            switch (value) {
                case "canary":
                    return Canary;
                default:
                    return null;
            }
        }

        public String toValue() {
            switch (this) {
                case Canary:
                    return "canary";
                default:
                    return null;
            }
        }
    }

    // using String and not the enum because fabric8 CRD generator doesn't allow to serialize as "canary" using a @JsonProperty for example
    // opened GitHub issue for this: https://github.com/fabric8io/kubernetes-client/issues/3411
    @NotNull
    private String name;
    //@NotNull
    private String principal;
    //@NotNull
    private String password;

    private SecretKeySelector principalRef;
    private SecretKeySelector passwordRef;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPrincipal() {
        return principal;
    }

    public void setPrincipal(String principal) {
        this.principal = principal;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public SecretKeySelector getPrincipalRef() {
        return principalRef;
    }

    public void setPrincipalRef(SecretKeySelector principalRef) {
        this.principalRef = principalRef;
    }

    public SecretKeySelector getPasswordRef() {
        return passwordRef;
    }

    public void setPasswordRef(SecretKeySelector passwordRef) {
        this.passwordRef = passwordRef;
    }
}
