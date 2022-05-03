package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.common.OperandUtils;
import org.bf2.operator.MockProfile;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaEndpoint;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.SecretKeySelectorBuilder;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;
import org.bf2.operator.resources.v1alpha1.ServiceAccountBuilder;
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
    void testCanaryPasswordSecretCopiedFromMasterSecret() {
        // Create a master-secret with key/values referenced later
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

        // Dummy ManagedKafka that references canary service account keys in master-secret
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

        // Secret does not exist before calling `createOrUpdate`
        Assertions.assertNull(canarySaslSecretResource.get());

        securitySecretManager.createOrUpdate(managedKafka);

        // Following `createOrUpdate`, the secret is created with the password from the master-secret
        Secret canarySaslSecret = canarySaslSecretResource.get();
        assertNotNull(canarySaslSecret);

        String encoded = canarySaslSecret.getData().get(SecuritySecretManager.SASL_PASSWORD);
        String actual = new String(Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8)));
        assertEquals("canary-password", actual);
    }

}
