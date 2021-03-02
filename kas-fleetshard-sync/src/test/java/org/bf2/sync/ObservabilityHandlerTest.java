package org.bf2.sync;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
@TestProfile(MockSyncProfile.class)
public class ObservabilityHandlerTest {

    @Inject
    KubernetesClient client;

    @Inject
    ObservabilityHandler observabilityHandler;

    @ConfigProperty(name = "observability.access_token")
    String accessToken;

    @ConfigProperty(name = "observability.channel")
    String channel;

    @ConfigProperty(name = "observability.repository")
    String repository;

    private Resource<ConfigMap> getConfigMapResource(){
        return client.configMaps().inNamespace(client.getNamespace())
                .withName(ObservabilityHandler.OBSERVABILITY_CONFIGMAP_NAME);
    }

    @Test
    public void testConfigMap() {

        assertEquals("test-token", this.accessToken);
        assertEquals("test", this.channel);
        assertEquals("test-repo", this.repository);

        ConfigMap map = getConfigMapResource().get();
        assertNull(map);

        observabilityHandler.loop();

        // lets call event handler
        map = getConfigMapResource().get();
        assertNotNull(map);

        // map verification
        assertEquals("test-token", map.getData().get("access_token"));
        assertEquals("test", map.getData().get("channel"));
        assertEquals("test-repo", map.getData().get("repository"));
        assertEquals("observability-operator", map.getMetadata().getLabels().get("configures"));

        // status verification
        map = observabilityHandler.createObservabilityConfigMapBuilder().editMetadata()
            .addToAnnotations("observability-operator/status", "accepted").endMetadata().build();
        getConfigMapResource().createOrReplace(map);
        assertTrue(observabilityHandler.isObservabilityRunning());
    }
}
