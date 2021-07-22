package org.bf2.operator.operands;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;

import java.util.Objects;

public class OperandReadiness {

    private final ManagedKafkaCondition.Status status;
    private final ManagedKafkaCondition.Reason reason;
    private final String message;

    public OperandReadiness(Status status, Reason reason, String message) {
        Objects.requireNonNull(status);
        this.status = status;
        this.reason = reason;
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public ManagedKafkaCondition.Reason getReason() {
        return reason;
    }

    public ManagedKafkaCondition.Status getStatus() {
        return status;
    }

}
