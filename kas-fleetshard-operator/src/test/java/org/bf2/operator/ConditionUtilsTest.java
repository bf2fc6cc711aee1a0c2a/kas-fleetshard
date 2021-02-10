package org.bf2.operator;


import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ConditionUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {"Installing", "Error", "Ready"})
    void testBuildUpdateCondition(String type) {
        ManagedKafkaCondition mkcondition = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.valueOf(type), "True");
        assertEquals("True", mkcondition.getStatus());
        assertEquals(type, mkcondition.getType());
        ConditionUtils.updateConditionStatus(mkcondition, "False");
        assertEquals("False", mkcondition.getStatus());
        assertEquals(type, mkcondition.getType());
    }

    @Test
    void testFindManagedKafkaCondition() {
        ManagedKafka mk = new ManagedKafkaBuilder()
                .withMetadata(
                        new ObjectMetaBuilder()
                                .withNamespace("test")
                                .withName("my-managed-kafka")
                                .build())
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewVersions()
                                    .withKafka("2.6.0")
                                    .withStrimzi("0.21.1")
                                .endVersions()
                                .build())
                .withStatus(
                        new ManagedKafkaStatusBuilder()
                                .addNewCondition()
                                .withStatus("False")
                                .withType(ManagedKafkaCondition.Type.Installing.name())
                                .endCondition()
                                .addNewCondition()
                                .withStatus("True")
                                .withType(ManagedKafkaCondition.Type.Ready.name())
                                .endCondition()
                                .build()
                )
                .build();

        assertFalse(ConditionUtils.findManagedKafkaCondition(mk.getStatus().getConditions(), ManagedKafkaCondition.Type.Error).isPresent());
        assertNotNull(ConditionUtils.findManagedKafkaCondition(mk.getStatus().getConditions(), ManagedKafkaCondition.Type.Installing).get());
        assertNotNull(ConditionUtils.findManagedKafkaCondition(mk.getStatus().getConditions(), ManagedKafkaCondition.Type.Ready).get());
    }
}
