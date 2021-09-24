package org.bf2.systemtest.framework;

import io.fabric8.kubernetes.api.model.Secret;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.k8s.KubeClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class KeycloakInstance {
    private static final Logger LOGGER = LogManager.getLogger(KeycloakInstance.class);
    public static final String KEYCLOAK_SECRET_NAME = "sso-x509-https-secret";
    public static final String KEYCLOAK_SECRET_CERT = "tls.crt";
    public static final String ADMIN_SECRET = "credential-example-keycloak";

    private final int jwksExpireSeconds = 500;
    private final int jwksRefreshSeconds = 400;
    private final String username;
    private final String password;
    private final String namespace;
    private final String httpsUri;
    private final String httpUri;

    private String validIssuerUri;
    private String jwksEndpointUri;
    private String oauthTokenEndpointUri;
    private String introspectionEndpointUri;
    private String userNameClaim;
    private String fallbackUserNameClaim;
    private final String keycloakCert;


    public KeycloakInstance(String namespace) {
        Secret secret = KubeClient.getInstance().client().secrets().inNamespace(namespace).withName(ADMIN_SECRET).get();
        this.username = new String(Base64.getDecoder().decode(secret.getData().get("ADMIN_USERNAME").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        this.password = new String(Base64.getDecoder().decode(secret.getData().get("ADMIN_PASSWORD").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        this.namespace = namespace;
        this.httpsUri = "keycloak." + namespace + ".svc:8443";
        this.httpUri = "keycloak-discovery." + namespace + ".svc:8080";
        this.validIssuerUri = "https://" + httpsUri + "/auth/realms/demo";
        this.jwksEndpointUri = "https://" + httpsUri + "/auth/realms/demo/protocol/openid-connect/certs";
        this.oauthTokenEndpointUri = "https://" + httpsUri + "/auth/realms/demo/protocol/openid-connect/token";
        this.introspectionEndpointUri = "https://" + httpsUri + "/auth/realms/demo/protocol/openid-connect/token/introspect";
        this.userNameClaim = "clientId";
        this.fallbackUserNameClaim = "preferred_username";
        this.keycloakCert = readKeycloakCert();
    }

    public void setRealm(String realmName, boolean tlsEnabled) {
        LOGGER.info("Replacing validIssuerUri: {} to pointing to {} realm", validIssuerUri, realmName);
        LOGGER.info("Replacing jwksEndpointUri: {} to pointing to {} realm", jwksEndpointUri, realmName);
        LOGGER.info("Replacing oauthTokenEndpointUri: {} to pointing to {} realm", oauthTokenEndpointUri, realmName);

        if (tlsEnabled) {
            LOGGER.info("Using HTTPS endpoints");
            validIssuerUri = "https://" + httpsUri + "/auth/realms/" + realmName;
            jwksEndpointUri = "https://" + httpsUri + "/auth/realms/" + realmName + "/protocol/openid-connect/certs";
            oauthTokenEndpointUri = "https://" + httpsUri + "/auth/realms/" + realmName + "/protocol/openid-connect/token";
        } else {
            LOGGER.info("Using HTTP endpoints");
            validIssuerUri = "http://" + httpUri + "/auth/realms/" + realmName;
            jwksEndpointUri = "http://" + httpUri + "/auth/realms/" + realmName + "/protocol/openid-connect/certs";
            oauthTokenEndpointUri = "http://" + httpUri + "/auth/realms/" + realmName + "/protocol/openid-connect/token";
        }
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getHttpsUri() {
        return httpsUri;
    }

    public String getHttpUri() {
        return httpUri;
    }

    public String getValidIssuerUri() {
        return validIssuerUri;
    }

    public void setValidIssuerUri(String validIssuerUri) {
        this.validIssuerUri = validIssuerUri;
    }

    public String getJwksEndpointUri() {
        return jwksEndpointUri;
    }

    public void setJwksEndpointUri(String jwksEndpointUri) {
        this.jwksEndpointUri = jwksEndpointUri;
    }

    public String getOauthTokenEndpointUri() {
        return oauthTokenEndpointUri;
    }

    public void setOauthTokenEndpointUri(String oauthTokenEndpointUri) {
        this.oauthTokenEndpointUri = oauthTokenEndpointUri;
    }

    public String getIntrospectionEndpointUri() {
        return introspectionEndpointUri;
    }

    public void setIntrospectionEndpointUri(String introspectionEndpointUri) {
        this.introspectionEndpointUri = introspectionEndpointUri;
    }

    public String getUserNameClaim() {
        return userNameClaim;
    }

    public void setUserNameClaim(String userNameClaim) {
        this.userNameClaim = userNameClaim;
    }

    public String getFallbackUserNameClaim() {
        return fallbackUserNameClaim;
    }

    public void setFallbackUserNameClaim(String fallbackUserNameClaim) {
        this.fallbackUserNameClaim = fallbackUserNameClaim;
    }

    public int getJwksExpireSeconds() {
        return jwksExpireSeconds;
    }

    public int getJwksRefreshSeconds() {
        return jwksRefreshSeconds;
    }

    public String getKeycloakCert() {
        return keycloakCert;
    }

    private String readKeycloakCert() {
        return new String(Base64.getMimeDecoder().decode(
                KubeClient.getInstance().client().secrets().inNamespace(namespace)
                        .withName(KEYCLOAK_SECRET_NAME).get().getData().get(KEYCLOAK_SECRET_CERT).getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "KeycloakInstance{" +
                "jwksExpireSeconds=" + jwksExpireSeconds + System.lineSeparator() +
                ", jwksRefreshSeconds=" + jwksRefreshSeconds + System.lineSeparator() +
                ", username='" + username + '\'' + System.lineSeparator() +
                ", password='" + password + '\'' + System.lineSeparator() +
                ", httpsUri='" + httpsUri + '\'' + System.lineSeparator() +
                ", httpUri='" + httpUri + '\'' + System.lineSeparator() +
                ", validIssuerUri='" + validIssuerUri + '\'' + System.lineSeparator() +
                ", jwksEndpointUri='" + jwksEndpointUri + '\'' + System.lineSeparator() +
                ", oauthTokenEndpointUri='" + oauthTokenEndpointUri + '\'' + System.lineSeparator() +
                ", introspectionEndpointUri='" + introspectionEndpointUri + '\'' + System.lineSeparator() +
                ", userNameClaim='" + userNameClaim + '\'' + System.lineSeparator() +
                ", keycloakCert=" + keycloakCert + '\'' + System.lineSeparator() +
                '}';
    }
}
