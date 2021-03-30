package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.sundr.builder.annotations.Buildable;
import lombok.ToString;

/**
 * Defines a condition related to the ManagedKafka instance status
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
public class ManagedKafkaCondition {

    public enum Type {
        Ready
    }

    public enum Reason {
        Installing,
        Deleted,
        Error,
        Rejected;
    }

    public enum Status {
        True,
        False,
        Unknown
    }

    private String type;
    private String reason;
    private String message;
    private String status;
    private String lastTransitionTime;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public ManagedKafkaCondition type(Type type) {
        this.type = (type == null ? null : type.name());
        return this;
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public ManagedKafkaCondition reason(Reason reason) {
        this.reason = (reason == null ? null : reason.name());
        return this;
    }

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setStatus(Status status) {
        this.status = (status == null ? null : status.name());
    }

    public String getLastTransitionTime() {
        return lastTransitionTime;
    }

    public void setLastTransitionTime(String lastTransitionTime) {
        this.lastTransitionTime = lastTransitionTime;
    }

}
