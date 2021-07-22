package org.bf2.operator.operands;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.utils.ManagedKafkaUtils;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class KafkaInstanceTest {

    @Test
    void testStatus() {
        // sample operand readiness
        Operand<ManagedKafka> error = mockOperand(new OperandReadiness(Status.False, Reason.Error, "I'm not well"));
        Operand<ManagedKafka> installing = mockOperand(new OperandReadiness(Status.False, Reason.Installing, "I'm installing"));
        Operand<ManagedKafka> ready = mockOperand(new OperandReadiness(Status.True, Reason.StrimziUpdating, null));
        Operand<ManagedKafka> unknown = mockOperand(new OperandReadiness(Status.Unknown, null, "I don't know"));

        OperandReadiness readiness = getReadiness(error, installing, ready);
        // installing trumps error
        assertEquals(Status.False, readiness.getStatus());
        assertEquals(Reason.Installing, readiness.getReason());
        assertEquals("I'm not well; I'm installing", readiness.getMessage());

        readiness = getReadiness(error, ready, error);
        // error trumps ready
        assertEquals(Status.False, readiness.getStatus());
        assertEquals(Reason.Error, readiness.getReason());
        assertEquals("I'm not well; I'm not well", readiness.getMessage());

        readiness = getReadiness(ready, ready, ready);
        // ready with reason
        assertEquals(Status.True, readiness.getStatus());
        assertEquals(Reason.StrimziUpdating, readiness.getReason());
        assertEquals("", readiness.getMessage());

        readiness = getReadiness(ready, ready, unknown);
        // ready with reason
        assertEquals(Status.Unknown, readiness.getStatus());
        assertEquals(Reason.StrimziUpdating, readiness.getReason());
        assertEquals("I don't know", readiness.getMessage());
    }

    private OperandReadiness getReadiness(Operand<ManagedKafka>... operands) {
        KafkaInstance instance = new KafkaInstance();
        instance.operands.addAll(Arrays.asList(operands));
        OperandReadiness readiness = instance.getReadiness(ManagedKafkaUtils.dummyManagedKafka("x"));
        return readiness;
    }

    private Operand<ManagedKafka> mockOperand(OperandReadiness readiness) {
        Operand<ManagedKafka> operand = Mockito.mock(Operand.class);
        Mockito.when(operand.getReadiness(Mockito.any())).thenReturn(readiness);
        return operand;
    }


}
