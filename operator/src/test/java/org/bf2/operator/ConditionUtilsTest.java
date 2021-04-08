package org.bf2.operator;


import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import org.bf2.common.ConditionUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ConditionUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {"Ready"})
    void testBuildUpdateCondition(String type) {
        ManagedKafkaCondition mkcondition = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.valueOf(type), Status.True);
        assertEquals("True", mkcondition.getStatus());
        assertEquals(type, mkcondition.getType());
        ConditionUtils.updateConditionStatus(mkcondition, Status.False, null);
        assertEquals("False", mkcondition.getStatus());
        assertEquals(type, mkcondition.getType());

        var mockCondition = Mockito.mock(ManagedKafkaCondition.class, Mockito.CALLS_REAL_METHODS);
        ConditionUtils.updateConditionStatus(mockCondition, Status.False, Reason.Deleted);
        Mockito.verify(mockCondition, Mockito.times(1)).setLastTransitionTime(Mockito.anyString());
        // only update if different
        ConditionUtils.updateConditionStatus(mockCondition, Status.False, Reason.Deleted);
        Mockito.verify(mockCondition, Mockito.times(1)).setLastTransitionTime(Mockito.anyString());
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
                                .withStatus(Status.True.name())
                                .withType(ManagedKafkaCondition.Type.Ready.name())
                                .endCondition()
                                .build()
                )
                .build();

        assertNotNull(ConditionUtils.findManagedKafkaCondition(mk.getStatus().getConditions(), ManagedKafkaCondition.Type.Ready).get());
    }
}
