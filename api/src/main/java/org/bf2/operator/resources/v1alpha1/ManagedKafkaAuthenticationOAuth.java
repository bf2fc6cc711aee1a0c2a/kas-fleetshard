package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
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
public class ManagedKafkaAuthenticationOAuth {

    private String clientId;
    private String clientSecret;
    private String tokenEndpointURI;
    private String jwksEndpointURI;
    private String validIssuerEndpointURI;
    private String userNameClaim;
    private String tlsTrustedCertificate;
    private String customClaimCheck;

    public String getCustomClaimCheck() {
        return customClaimCheck;
    }

    public void setCustomClaimCheck(String customClaimCheck) {
        this.customClaimCheck = customClaimCheck;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getTokenEndpointURI() {
        return tokenEndpointURI;
    }

    public void setTokenEndpointURI(String tokenEndpointURI) {
        this.tokenEndpointURI = tokenEndpointURI;
    }

    public String getJwksEndpointURI() {
        return jwksEndpointURI;
    }

    public void setJwksEndpointURI(String jwksEndpointURI) {
        this.jwksEndpointURI = jwksEndpointURI;
    }

    public String getValidIssuerEndpointURI() {
        return validIssuerEndpointURI;
    }

    public void setValidIssuerEndpointURI(String validIssuerEndpointURI) {
        this.validIssuerEndpointURI = validIssuerEndpointURI;
    }

    public String getUserNameClaim() {
        return userNameClaim;
    }

    public void setUserNameClaim(String userNameClaim) {
        this.userNameClaim = userNameClaim;
    }

    public String getTlsTrustedCertificate() {
        return tlsTrustedCertificate;
    }

    public void setTlsTrustedCertificate(String tlsTrustedCertificate) {
        this.tlsTrustedCertificate = tlsTrustedCertificate;
    }
}
