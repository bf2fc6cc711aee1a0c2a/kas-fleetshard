package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperandTest {

    @Test
    void deploymentReadinessTest() {
        OperandReadiness readiness = Operand.getDeploymentReadiness(null, "x");
        assertEquals(Status.False, readiness.getStatus());
        assertEquals(Reason.Installing, readiness.getReason());
        assertEquals("Deployment x does not exist", readiness.getMessage());

        DeploymentBuilder builder = new DeploymentBuilder().withNewMetadata().withName("x").endMetadata();

        readiness = Operand.getDeploymentReadiness(builder.build(), "x");
        assertEquals(Status.False, readiness.getStatus());
        assertEquals(Reason.Installing, readiness.getReason());
        assertEquals("Deployment x has no progressing condition", readiness.getMessage());

        readiness = Operand.getDeploymentReadiness(builder.withNewStatus().withConditions(new DeploymentConditionBuilder()
                .withType("Progressing")
                .withStatus("False")
                .withMessage("message")
                .build()).endStatus().build(), "x-operand");
        assertEquals(Status.False, readiness.getStatus());
        assertEquals(Reason.Error, readiness.getReason());
        assertEquals("message", readiness.getMessage());
    }

}
