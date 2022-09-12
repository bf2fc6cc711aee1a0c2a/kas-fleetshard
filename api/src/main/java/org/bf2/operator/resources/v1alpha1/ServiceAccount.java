package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
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
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
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
    private String principal;
    private String password;
    private SecretKeySelector principalRef;
    private SecretKeySelector passwordRef;

}
