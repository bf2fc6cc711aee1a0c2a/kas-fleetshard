package org.bf2.operator.operands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfigurationBuilder;
import org.bf2.test.mock.QuarkusKubeMockServer;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTestResource(QuarkusKubeMockServer.class)
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

        this.observabilityManager.createOrUpdateObservabilitySecret(config);

        // lets call event handler
        Secret secret = observabilityManager.observabilitySecretResource().get();
        assertNotNull(secret);

        // the mock informermanager should be immediately updated, but it should
        // not be seen as running
        assertNotNull(observabilityManager.cachedObservabilitySecret());
        assertFalse(observabilityManager.isObservabilityRunning());

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
        secret = ObservabilityManager.createObservabilitySecretBuilder(client.getNamespace(), config).editMetadata()
                .addToAnnotations(ObservabilityManager.OBSERVABILITY_OPERATOR_STATUS, ObservabilityManager.ACCEPTED).endMetadata().build();
        observabilityManager.observabilitySecretResource().createOrReplace(secret);

        secret = observabilityManager.observabilitySecretResource().get();
        assertTrue(ObservabilityManager.isObservabilityStatusAccepted(secret));

        this.observabilityManager.createOrUpdateObservabilitySecret(config);

        // no-op update and make sure the flag is not flipped
        secret = observabilityManager.observabilitySecretResource().get();
        assertTrue(ObservabilityManager.isObservabilityStatusAccepted(secret));
    }
}
