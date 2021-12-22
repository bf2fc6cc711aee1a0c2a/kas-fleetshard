package org.bf2.operator.operands;

import io.javaoperatorsdk.operator.api.Context;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.utils.ManagedKafkaUtils;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;

@QuarkusTest
public class KafkaInstanceTest {

    @InjectMock
    KafkaCluster kafkaCluster;

    @InjectMock
    Canary canary;

    @InjectMock
    AdminServer adminServer;

    @Inject
    KafkaInstance kafkaInstance;

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

    @Test
    void deleteOrderCanaryDeleteBeforeKafkaCluster() {
        Context<ManagedKafka> context = Mockito.mock(Context.class);
        ManagedKafka managedKafka = ManagedKafkaUtils.dummyManagedKafka("x");

        kafkaInstance.delete(managedKafka, context);

        InOrder inOrder = inOrder(canary, kafkaCluster);
        inOrder.verify(canary).delete(managedKafka, context);
        inOrder.verify(kafkaCluster).delete(managedKafka, context);
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
