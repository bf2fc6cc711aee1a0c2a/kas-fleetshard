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
public class ImageManagerTest {

    @Inject
    KubernetesClient client;

    @Inject
    ImageManager imageManager;

    @AfterEach
    public void cleanup() {
        imageManager.resetImages();
    }

    @Test
    void testImageOverride() {
        String versionString = "strimzi-cluster-operator-0.26-1";
        String defaultVersion = imageManager.getAdminApiImage(versionString);

        imageManager.updateImages(new ConfigMapBuilder().withNewMetadata()
                .withName(versionString)
                .endMetadata()
                .withData(Collections.singletonMap(ImageManager.IMAGES_YAML, "canary: something"))
                .build());

        String override = imageManager.getCanaryImage(versionString);

        assertEquals("something", override);
        assertNotEquals(defaultVersion, override);
    }

}
