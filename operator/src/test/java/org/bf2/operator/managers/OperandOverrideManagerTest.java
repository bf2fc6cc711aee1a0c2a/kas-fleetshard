package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.operator.MockProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTestResource(KubernetesServerTestResource.class)
@TestProfile(MockProfile.class)
@QuarkusTest
public class OperandOverrideManagerTest {

    @Inject
    KubernetesClient client;

    @Inject
    OperandOverrideManager overrideManager;

    @AfterEach
    public void cleanup() {
        overrideManager.resetOverrides();
    }

    @Test
    void testImageOverride() {
        String versionString = "strimzi-cluster-operator-0.26-1";
        String defaultVersion = overrideManager.getCanaryImage(versionString);

        overrideManager.updateOverrides(new ConfigMapBuilder().withNewMetadata()
                .withName(versionString)
                .endMetadata()
                .withData(Collections.singletonMap(OperandOverrideManager.OPERANDS_YAML,
                          "canary: \n"
                        + "  image: something\n"
                        + "  notused: value\n"
                        + "  init: \n"
                        + "    image: somethingelse\n"))
                .build());

        String override = overrideManager.getCanaryImage(versionString);

        assertEquals("something", override);
        assertNotEquals(defaultVersion, override);

        String initOverride = overrideManager.getCanaryInitImage(versionString);

        assertEquals("somethingelse", initOverride);
    }

}
