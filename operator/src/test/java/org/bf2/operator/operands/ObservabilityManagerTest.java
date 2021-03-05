package org.bf2.operator.operands;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.fabric8.kubernetes.api.model.ConfigMap;
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
    public void testConfigMap() {
        client.getConfiguration().setNamespace("test");

        ConfigMap map = observabilityManager.observabilityConfigMapResource().get();
        assertNull(map);

        ObservabilityConfiguration config = new ObservabilityConfigurationBuilder()
                .withAccessToken("test-token")
                .withChannel("test")
                .withRepository("test-repo")
                .build();

        this.observabilityManager.createOrUpdateObservabilityConfigMap(config);

        // lets call event handler
        map = observabilityManager.observabilityConfigMapResource().get();
        assertNotNull(map);

        ObservabilityConfiguration mapConfig = new ObservabilityConfigurationBuilder()
                .withAccessToken(map.getData().get(ObservabilityManager.OBSERVABILITY_ACCESS_TOKEN))
                .withChannel(map.getData().get(ObservabilityManager.OBSERVABILITY_CHANNEL))
                .withRepository(map.getData().get(ObservabilityManager.OBSERVABILITY_REPOSITORY))
                .build();

        // map verification
        assertTrue(config.equals(mapConfig));
        assertEquals("observability-operator", map.getMetadata().getLabels().get("configures"));

        // status verification, the Informers do not work in test framework thus direct verification
        map = ObservabilityManager.createObservabilityConfigMapBuilder(client.getNamespace(), config).editMetadata()
            .addToAnnotations("observability-operator/status", "accepted").endMetadata().build();
        observabilityManager.observabilityConfigMapResource().createOrReplace(map);
        assertTrue(ObservabilityManager.isObservabilityStatusAccepted(map));
    }
}
