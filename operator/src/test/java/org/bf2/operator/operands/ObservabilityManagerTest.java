package org.bf2.operator.operands;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfigurationBuilder;
import org.bf2.test.mock.QuarkusKubeMockServer;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

@QuarkusTestResource(QuarkusKubeMockServer.class)
@QuarkusTest
public class ObservabilityManagerTest {

    @Inject
    KubernetesClient client;

    @Inject
    ObservabilityManager observabilityManager;

    @Test
    public void testObservabilitySecret() {
        client.getConfiguration().setNamespace("test");

        Secret secret = observabilityManager.observabilitySecretResource().get();
        assertNull(secret);

        ObservabilityConfiguration config = new ObservabilityConfigurationBuilder()
                .withAccessToken("test-token")
                .withChannel("test")
                .withRepository("test-repo")
                .withTag("tag")
                .build();

        this.observabilityManager.createOrUpdateObservabilitySecret(config);

        // lets call event handler
        secret = observabilityManager.observabilitySecretResource().get();
        assertNotNull(secret);

        ObservabilityConfiguration secretConfig = new ObservabilityConfigurationBuilder()
                .withAccessToken(secret.getData().get(ObservabilityManager.OBSERVABILITY_ACCESS_TOKEN))
                .withChannel(secret.getData().get(ObservabilityManager.OBSERVABILITY_CHANNEL))
                .withTag(secret.getData().get(ObservabilityManager.OBSERVABILITY_TAG))
                .withRepository(secret.getData().get(ObservabilityManager.OBSERVABILITY_REPOSITORY))
                .build();

        // secret verification
        assertTrue(config.equals(secretConfig));
        assertEquals("observability-operator", secret.getMetadata().getLabels().get("configures"));

        // status verification, the Informers do not work in test framework thus direct verification
        secret = ObservabilityManager.createObservabilitySecretBuilder(client.getNamespace(), config).editMetadata()
            .addToAnnotations("observability-operator/status", "accepted").endMetadata().build();
        observabilityManager.observabilitySecretResource().createOrReplace(secret);
        assertTrue(ObservabilityManager.isObservabilityStatusAccepted(secret));
    }
}
