package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationBuilder;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class DrainCleanerManagerTest {

    @Inject
    DrainCleanerManager drainCleanerManager;

    @Test
    void testOverlappingWebhookConfigs() {
        assertFalse(drainCleanerManager.isDrainCleanerWebhookFound());

        var vwhc = new ValidatingWebhookConfigurationBuilder().withNewMetadata()
                .withName("config")
                .endMetadata()
                .build();

        drainCleanerManager.added(vwhc);

        assertTrue(drainCleanerManager.isDrainCleanerWebhookFound());

        // no change with a second add event
        drainCleanerManager.added(vwhc);

        assertTrue(drainCleanerManager.isDrainCleanerWebhookFound());

        drainCleanerManager.deleted(false, vwhc);

        assertTrue(drainCleanerManager.isDrainCleanerWebhookFound());

        drainCleanerManager.deleted(true, vwhc);

        assertFalse(drainCleanerManager.isDrainCleanerWebhookFound());
    }

}
