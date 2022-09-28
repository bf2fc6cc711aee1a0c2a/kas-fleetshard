package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Defines the configuration for the Kafka instance authentication against an OAuth server
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
public class ManagedKafkaAuthenticationOAuth {

    private String clientId;
    private String clientSecret;
    private SecretKeySelector clientIdRef;
    private SecretKeySelector clientSecretRef;
    private String tokenEndpointURI;
    private String jwksEndpointURI;
    private String validIssuerEndpointURI;
    private String userNameClaim;
    private String fallbackUserNameClaim;
    private String tlsTrustedCertificate;
    private String customClaimCheck;
    private Long maximumSessionLifetime;

}
