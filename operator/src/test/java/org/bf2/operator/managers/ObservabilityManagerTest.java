package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfigurationBuilder;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class ObservabilityManagerTest {

    @Inject
    KubernetesClient client;

    @Inject
    ObservabilityManager observabilityManager;

    private final Base64.Decoder decoder = Base64.getDecoder();

    @Test
    public void testObservabilitySecret() {
        client.getConfiguration().setNamespace("test");

        ObservabilityConfiguration config = new ObservabilityConfigurationBuilder()
                .withAccessToken("test-token")
                .withChannel("test")
                .withRepository("test-repo")
                .withTag("tag")
                .build();

        String ownerName = "SampleOwner";
        Secret owner = client.secrets().inNamespace(client.getNamespace()).withName(ownerName)
            .create(new SecretBuilder()
                .withNewMetadata()
                .withNamespace(client.getNamespace())
                .withName(ownerName)
            .endMetadata()
            .addToData("key", "value").build());

        this.observabilityManager.createOrUpdateObservabilitySecret(config, owner);

        // make sure the owner reference exists
        Secret secret = observabilityManager.observabilitySecretResource().get();
        assertNotNull(secret);
        assertNotNull(secret.getMetadata().getOwnerReferences().get(0));

        // now remove and make sure it comes back
        this.observabilityManager.observabilitySecretResource().edit(s -> {
            s.getMetadata().setOwnerReferences(null);
            return s;
        });

        this.observabilityManager.createOrUpdateObservabilitySecret(config, owner);

        // lets call event handler
        secret = observabilityManager.observabilitySecretResource().get();
        assertNotNull(secret);
        assertNotNull(secret.getMetadata().getOwnerReferences().get(0));

        // the mock informermanager should be immediately updated, but it should
        // not be seen as running
        assertNotNull(observabilityManager.cachedObservabilitySecret());
        assertFalse(observabilityManager.isObservabilityRunning());
        assertFalse(secret.getMetadata().getOwnerReferences().isEmpty());

        ObservabilityConfiguration secretConfig = new ObservabilityConfigurationBuilder()
                .withAccessToken(new String(decoder.decode(secret.getData().get(ObservabilityManager.OBSERVABILITY_ACCESS_TOKEN))))
                .withChannel(new String(decoder.decode(secret.getData().get(ObservabilityManager.OBSERVABILITY_CHANNEL))))
                .withTag(new String(decoder.decode(secret.getData().get(ObservabilityManager.OBSERVABILITY_TAG))))
                .withRepository(new String(decoder.decode(secret.getData().get(ObservabilityManager.OBSERVABILITY_REPOSITORY))))
                .build();

        // secret verification
        assertEquals(secretConfig, config);
        assertEquals("observability-operator", secret.getMetadata().getLabels().get("configures"));

        // status verification, the Informers do not work in test framework thus direct verification
        SecretBuilder builder = new SecretBuilder();
        ObservabilityManager.createObservabilitySecret(client.getNamespace(), config, builder);
        secret = builder.editMetadata()
                .addToAnnotations(ObservabilityManager.OBSERVABILITY_OPERATOR_STATUS, ObservabilityManager.ACCEPTED).endMetadata().build();
        observabilityManager.observabilitySecretResource().createOrReplace(secret);

        secret = observabilityManager.observabilitySecretResource().get();
        assertTrue(ObservabilityManager.isObservabilityStatusAccepted(secret));

        this.observabilityManager.createOrUpdateObservabilitySecret(config, owner);

        // no-op update and make sure the flag is not flipped
        secret = observabilityManager.observabilitySecretResource().get();
        assertTrue(ObservabilityManager.isObservabilityStatusAccepted(secret));
    }
}
