package org.bf2.operator.operands;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfigurationBuilder;
import org.bf2.test.mock.QuarkusKubeMockServer;
import org.bf2.test.mock.QuarkusKubernetesMockServer;
import org.junit.jupiter.api.Test;

@QuarkusTestResource(QuarkusKubeMockServer.class)
@QuarkusTest
public class ObservabilityClientTest {

    @QuarkusKubernetesMockServer
    static KubernetesServer server;

    private Resource<ConfigMap> getConfigMapResource(){
        return server.getClient().configMaps().inNamespace(server.getClient().getNamespace())
                .withName(ObservabilityClient.OBSERVABILITY_CONFIGMAP_NAME);
    }

    @Test
    public void testConfigMap() {
        KubernetesClient client = server.getClient();
        ConfigMap map = getConfigMapResource().get();
        assertNull(map);

        ObservabilityConfiguration config = new ObservabilityConfigurationBuilder()
                .withAccessToken("test-token")
                .withChannel("test")
                .withRepository("test-repo")
                .build();

        ObservabilityClient.createOrUpdateObservabilityConfigMap(client, client.getNamespace(), config);

        // lets call event handler
        map = getConfigMapResource().get();
        assertNotNull(map);

        // map verification
        assertEquals("test-token", map.getData().get("access_token"));
        assertEquals("test", map.getData().get("channel"));
        assertEquals("test-repo", map.getData().get("repository"));
        assertEquals("observability-operator", map.getMetadata().getLabels().get("configures"));

        // status verification
        map = ObservabilityClient.createObservabilityConfigMapBuilder(client.getNamespace(), config).editMetadata()
            .addToAnnotations("observability-operator/status", "accepted").endMetadata().build();
        getConfigMapResource().createOrReplace(map);
        assertTrue(ObservabilityClient.isObservabilityRunning(client, client.getNamespace()));
    }
}
