package org.bf2.operator.eventhandlers;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.inject.Inject;

import java.util.Base64;

import org.bf2.operator.InformerManager;
import org.bf2.test.mock.QuarkusKubeMockServer;
import org.bf2.test.mock.QuarkusKubernetesMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTestResource(QuarkusKubeMockServer.class)
@QuarkusTest
public class ObservabilityHandlerTest {

    @QuarkusKubernetesMockServer
    static KubernetesServer server;

    @Inject
    ObservabilityHandler observabilityHandler;

    @BeforeEach
    public void beforeEach() {
        server.getClient().secrets().create(new SecretBuilder().withNewMetadata()
                .withNamespace(server.getClient().getNamespace())
                .withName(InformerManager.SECRET_NAME)
            .endMetadata()
            .addToData("observability.access_token", Base64.getEncoder().encodeToString("test-token".getBytes()))
            .addToData("observability.channel", Base64.getEncoder().encodeToString("test".getBytes()))
            .addToData("observability.repository", Base64.getEncoder().encodeToString("test-repo".getBytes()))
            .build());
    }

    @AfterEach
    public void afterEach() {
        server.getClient().secrets().inNamespace(server.getClient().getNamespace())
            .withName(InformerManager.SECRET_NAME).delete();
    }

    private Resource<Secret> getSecretResource(){
        return server.getClient().secrets().inNamespace(server.getClient()
                .getNamespace()).withName(InformerManager.SECRET_NAME);
    }

    private Resource<ConfigMap> getConfigMapResource(){
        return server.getClient().configMaps().inNamespace(server.getClient().getNamespace())
                .withName(ObservabilityHandler.OBSERVABILITY_CONFIGMAP_NAME);
    }

    @Test
    public void testConfigMap() {
        Secret s = getSecretResource().get();
        assertNotNull(s);

        assertEquals("test-token", ObservabilityHandler.getSecretData(s,"observability.access_token"));
        assertEquals("test", ObservabilityHandler.getSecretData(s,"observability.channel"));
        assertEquals("test-repo", ObservabilityHandler.getSecretData(s,"observability.repository"));

        // lets call event handler
        observabilityHandler.onAdd(s);
        ConfigMap map = getConfigMapResource().get();
        assertNotNull(map);

        // map verification
        assertEquals("test-token", map.getData().get("access_token"));
        assertEquals("test", map.getData().get("channel"));
        assertEquals("test-repo", map.getData().get("repository"));
        assertEquals("observability-operator", map.getMetadata().getLabels().get("configures"));

        // status verification
        map = observabilityHandler.createObservabilityConfigMapBuilder(s).editMetadata()
            .addToAnnotations("observability-operator/status", "accepted").endMetadata().build();
        getConfigMapResource().createOrReplace(map);
        assertTrue(observabilityHandler.isObservabilityRunning());

        // delete the secret
        observabilityHandler.onDelete(s, true);
        map = getConfigMapResource().get();
        assertNull(map);
    }
}
