package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;

@Buildable
public class ManagedKafkaCondition {

    public enum Type {
        Installing,
        Ready,
        Error;

        public static Type from(String value) {
            switch (value) {
                case "Installing":
                    return Installing;
                case "Ready":
                    return Ready;
                case "Error":
                    return Error;
            }
            throw new IllegalArgumentException("Invalid value " + value);
        }
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

    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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

    public String getLastTransitionTime() {
        return lastTransitionTime;
    }

    public void setLastTransitionTime(String lastTransitionTime) {
        this.lastTransitionTime = lastTransitionTime;
    }

}
