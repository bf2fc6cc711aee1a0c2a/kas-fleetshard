package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.javaoperatorsdk.operator.api.config.ControllerConfiguration;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerEventFilterTest {

    ControllerEventFilter target;
    ControllerConfiguration<HasMetadata> mockConfiguration;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        target = new ControllerEventFilter();
        mockConfiguration = Mockito.mock(ControllerConfiguration.class);
    }

    @Test
    void testAddResourceAccepted() {
        assertTrue(target.accept(null, new ManagedKafka()));
    }

    @Test
    void testChangedGenerationAccepted() {
        ManagedKafka oldMk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withGeneration(1L)
                .endMetadata()
                .build();
        ManagedKafka newMk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withGeneration(2L)
                .endMetadata()
                .build();

        assertTrue(target.accept(oldMk, newMk));
    }

    @Test
    void testChangedAnnotationsAccepted() {
        ManagedKafka oldMk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withAnnotations(Map.of("anno1", "v1"))
                .endMetadata()
                .build();
        ManagedKafka newMk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withAnnotations(Map.of("anno2", "v2"))
                .endMetadata()
                .build();

        assertTrue(target.accept(oldMk, newMk));
    }

    @Test
    void testChangedLabelsAccepted() {
        ManagedKafka oldMk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withLabels(Map.of("label1", "v1"))
                .endMetadata()
                .build();
        ManagedKafka newMk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withLabels(Map.of("label2", "v2"))
                .endMetadata()
                .build();

        assertTrue(target.accept(oldMk, newMk));
    }

    @Test
    void testUnchangedNotAccepted() {
        ManagedKafka oldMk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withGeneration(1L)
                    .withAnnotations(Map.of("anno1", "v1"))
                    .withLabels(Map.of("label1", "v1"))
                .endMetadata()
                .build();
        ManagedKafka newMk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withGeneration(1L)
                    .withAnnotations(Map.of("anno1", "v1"))
                    .withLabels(Map.of("label1", "v1"))
                .endMetadata()
                .build();

        assertFalse(target.accept(oldMk, newMk));
    }
}
