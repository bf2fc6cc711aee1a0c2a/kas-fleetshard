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

        ConfigMap map = observabilityManager.observabilityConfigMap().get();
        assertNull(map);

        ObservabilityConfiguration config = new ObservabilityConfigurationBuilder()
                .withAccessToken("test-token")
                .withChannel("test")
                .withRepository("test-repo")
                .build();

        this.observabilityManager.createOrUpdateObservabilityConfigMap(config);

        // lets call event handler
        map = observabilityManager.observabilityConfigMap().get();
        assertNotNull(map);

        // map verification
        assertEquals("test-token", map.getData().get("access_token"));
        assertEquals("test", map.getData().get("channel"));
        assertEquals("test-repo", map.getData().get("repository"));
        assertEquals("observability-operator", map.getMetadata().getLabels().get("configures"));

        // status verification
        map = ObservabilityManager.createObservabilityConfigMapBuilder(client.getNamespace(), config).editMetadata()
            .addToAnnotations("observability-operator/status", "accepted").endMetadata().build();
        observabilityManager.observabilityConfigMap().createOrReplace(map);
        assertTrue(this.observabilityManager.isObservabilityRunning());
    }
}
