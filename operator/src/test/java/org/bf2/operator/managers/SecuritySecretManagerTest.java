package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.operator.MockProfile;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuthBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaEndpoint;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaEndpointBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.SecretKeySelectorBuilder;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;
import org.bf2.operator.resources.v1alpha1.ServiceAccountBuilder;
import org.bf2.operator.resources.v1alpha1.TlsKeyPairBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@TestProfile(MockProfile.class)
@QuarkusTest
class SecuritySecretManagerTest {

    @Inject
    KubernetesClient client;

    @Inject
    SecuritySecretManager securitySecretManager;

    @BeforeEach
    void setup() {
        client.secrets().inNamespace(client.getNamespace()).delete();
    }

    @Test
    void testCanarySecretCopiedFromMasterSecret() {
        client.secrets()
            .inNamespace(client.getNamespace())
            .create(new SecretBuilder()
                    .withNewMetadata()
                        .withName("test-master-secret")
                    .endMetadata()
                    .withData(Map.of(
                            "canary-principal-key", "canary-principal",
                            "canary-password-key", "canary-password"))
                    .build());

        ManagedKafka managedKafka = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withName("test")
                .endMetadata()
                .withSpec(new ManagedKafkaSpecBuilder()
                        .withEndpoint(new ManagedKafkaEndpoint())
                        .withServiceAccounts(new ServiceAccountBuilder()
                                .withName(ServiceAccount.ServiceAccountName.Canary.toValue())
                                .withPrincipalRef(new SecretKeySelectorBuilder()
                                        .withName("test-master-secret")
                                        .withKey("canary-principal-key")
                                        .build())
                                .withPasswordRef(new SecretKeySelectorBuilder()
                                        .withName("test-master-secret")
                                        .withKey("canary-password-key")
                                        .build())
                                .build())
                        .build())
                .build();

        Resource<Secret> canarySaslSecretResource = client.secrets()
                .inNamespace(client.getNamespace())
                .withName(SecuritySecretManager.canarySaslSecretName(managedKafka));

        Assertions.assertNull(canarySaslSecretResource.get());

        securitySecretManager.createOrUpdate(managedKafka);

        Secret canarySaslSecret = canarySaslSecretResource.get();
        assertNotNull(canarySaslSecret);

        String encodedCanaryPrincipal = canarySaslSecret.getData().get(SecuritySecretManager.SASL_PRINCIPAL);
        String actualCanaryPrincipal = new String(Base64.getDecoder().decode(encodedCanaryPrincipal.getBytes(StandardCharsets.UTF_8)));
        assertEquals("canary-principal", actualCanaryPrincipal);

        String encodedCanaryPassword = canarySaslSecret.getData().get(SecuritySecretManager.SASL_PASSWORD);
        String actualCanaryPassword = new String(Base64.getDecoder().decode(encodedCanaryPassword.getBytes(StandardCharsets.UTF_8)));
        assertEquals("canary-password", actualCanaryPassword);


    }

    @Test
    void testKafkaTlsSecretFromMasterSecret(){
        client.secrets()
            .inNamespace(client.getNamespace())
            .create(new SecretBuilder()
                    .withNewMetadata()
                        .withName("test-master-secret")
                    .endMetadata()
                    .withData(Map.of(
                            "kafka-tls-cert", "tls-crt",
                            "kafka-tls-key", "tls-key"))
                    .build());

        ManagedKafka managedKafka = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withName("test")
                .endMetadata()
                .withSpec(new ManagedKafkaSpecBuilder()
                        .withEndpoint(new ManagedKafkaEndpointBuilder()
                                .withTls(new TlsKeyPairBuilder()
                                        .withCertRef(new SecretKeySelectorBuilder()
                                                .withName("test-master-secret")
                                                .withKey("kafka-tls-cert")
                                                .build())
                                        .withKeyRef(new SecretKeySelectorBuilder()
                                                .withName("test-master-secret")
                                                .withKey("kafka-tls-key")
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        Resource<Secret> kafkaTlsSecretResource = client.secrets()
                .inNamespace(client.getNamespace())
                .withName(SecuritySecretManager.kafkaTlsSecretName(managedKafka));
        Assertions.assertNull(kafkaTlsSecretResource.get());

        securitySecretManager.createOrUpdate(managedKafka);
        Secret kafkaTlsSecret = kafkaTlsSecretResource.get();
        assertNotNull(kafkaTlsSecret);

        String encodeKafkaTlsCert = kafkaTlsSecret.getData().get("tls.crt");
        String actualKafkaTlsCert = new String(Base64.getDecoder().decode(encodeKafkaTlsCert.getBytes(StandardCharsets.UTF_8)));
        assertEquals("tls-crt", actualKafkaTlsCert);

        String encodeKafkaTlsKey = kafkaTlsSecret.getData().get("tls.key");
        String actualKafkaTlsKey = new String(Base64.getDecoder().decode(encodeKafkaTlsKey.getBytes(StandardCharsets.UTF_8)));
        assertEquals("tls-key", actualKafkaTlsKey);
    }

    @Test
    void testSsoClientSecretFromMasterSecret(){
        client.secrets()
             .inNamespace(client.getNamespace())
             .create(new SecretBuilder()
                     .withNewMetadata()
                            .withName("test-master-secret")
                     .endMetadata()
                     .withData(Map.of(
                                "sso-client-secret", "sso-client-secret"))
                     .build());

        ManagedKafka managedKafka = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withName("test")
                .endMetadata()
                .withSpec(new ManagedKafkaSpecBuilder()
                        .withEndpoint(new ManagedKafkaEndpoint())
                        .withOauth(new ManagedKafkaAuthenticationOAuthBuilder()
                                .withClientSecretRef( new SecretKeySelectorBuilder()
                                        .withName("test-master-secret")
                                        .withKey("sso-client-secret")
                                        .build())
                                .withTlsTrustedCertificate("sso-keycloak-crt")
                                .build())
                        .build())
                .build();

        Resource<Secret> ssoSecretResource = client.secrets()
                .inNamespace(client.getNamespace())
                .withName(SecuritySecretManager.ssoClientSecretName(managedKafka));
        Assertions.assertNull(ssoSecretResource.get());

        securitySecretManager.createOrUpdate(managedKafka);
        Secret ssoClientSecret = ssoSecretResource.get();
        assertNotNull(ssoClientSecret);

        String encodedClientSecret = ssoClientSecret.getData().get("ssoClientSecret");
        String actualClientSecret = new String(Base64.getDecoder().decode(encodedClientSecret.getBytes(StandardCharsets.UTF_8)));
        assertEquals("sso-client-secret", actualClientSecret);
    }

}
