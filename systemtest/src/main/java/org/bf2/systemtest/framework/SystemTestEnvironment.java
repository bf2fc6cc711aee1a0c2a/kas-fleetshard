package org.bf2.systemtest.framework;

import org.bf2.test.Environment;

import java.util.Objects;

public class SystemTestEnvironment extends Environment {

    /*
     * Vars for default managed kafka CR
     */
    private static final String BOOTSTRAP_HOST_DOMAIN_ENV = "BOOTSTRAP_HOST_DOMAIN";
    private static final String OAUTH_CLIENT_SECRET_ENV = "OAUTH_CLIENT_SECRET";
    private static final String OAUTH_USER_CLAIM_ENV = "OAUTH_USER_CLAIM";
    private static final String OAUTH_JWKS_ENDPOINT_ENV = "OAUTH_JWKS_ENDPOINT";
    private static final String OAUTH_TOKEN_ENDPOINT_ENV = "OAUTH_TOKEN_ENDPOINT";
    private static final String OAUTH_ISSUER_ENDPOINT_ENV = "OAUTH_ISSUER_ENDPOINT";
    private static final String OAUTH_CLIENT_ID_ENV = "OAUTH_CLIENT_ID";
    private static final String OAUTH_TLS_CERT_ENV = "OAUTH_TLS_CERT";
    private static final String ENDPOINT_TLS_CERT_ENV = "ENDPOINT_TLS_CERT";
    private static final String ENDPOINT_TLS_KEY_ENV = "ENDPOINT_TLS_KEY";
    private static final String STRIMZI_VERSION_ENV = "STRIMZI_VERSION";

    private static final String SKIP_TEARDOWN_ENV = "SKIP_TEARDOWN";
    private static final String SKIP_DEPLOY_ENV = "SKIP_DEPLOY";

    public static final String DUMMY_CERT = "cert";
    public static final String DUMMY_OAUTH_JWKS_URI = "jwks_endpoint";

    public static final String BOOTSTRAP_HOST_DOMAIN = getOrDefault(BOOTSTRAP_HOST_DOMAIN_ENV, "my-domain.com");
    public static final String OAUTH_CLIENT_SECRET = getOrDefault(OAUTH_CLIENT_SECRET_ENV, "client_secret");
    public static final String OAUTH_USER_CLAIM = getOrDefault(OAUTH_USER_CLAIM_ENV, "preferred_username");
    public static final String OAUTH_JWKS_ENDPOINT = getOrDefault(OAUTH_JWKS_ENDPOINT_ENV, DUMMY_OAUTH_JWKS_URI);
    public static final String OAUTH_TOKEN_ENDPOINT = getOrDefault(OAUTH_TOKEN_ENDPOINT_ENV, "token_endpoint");
    public static final String OAUTH_ISSUER_ENDPOINT = getOrDefault(OAUTH_ISSUER_ENDPOINT_ENV, "issuer_endpoint");
    public static final String OAUTH_CLIENT_ID = getOrDefault(OAUTH_CLIENT_ID_ENV, "client_id");
    public static final String OAUTH_TLS_CERT = getOrDefault(OAUTH_TLS_CERT_ENV, DUMMY_CERT);
    public static final String ENDPOINT_TLS_CERT = getOrDefault(ENDPOINT_TLS_CERT_ENV, DUMMY_CERT);
    public static final String ENDPOINT_TLS_KEY = getOrDefault(ENDPOINT_TLS_KEY_ENV, "key");
    public static final String STRIMZI_VERSION = getOrDefault(STRIMZI_VERSION_ENV, Objects.requireNonNullElse(System.getProperty("strimziVersion"), versionFromMetaInf("io.strimzi/api")));

    public static final boolean SKIP_TEARDOWN = getOrDefault(SKIP_TEARDOWN_ENV, Boolean::parseBoolean, false);
    public static final boolean SKIP_DEPLOY = getOrDefault(SKIP_DEPLOY_ENV, Boolean::parseBoolean, false);

    public static void logEnvironment() {
        Environment.logEnvironment();
    }

}
