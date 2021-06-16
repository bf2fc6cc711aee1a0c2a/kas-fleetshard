package io.kafka.performance;

public class UnschedulablePodException extends RuntimeException {
    public UnschedulablePodException(String format) {
        super(format);
    }

    public UnschedulablePodException(String message, Throwable cause) {
        super(message, cause);
    }

    public UnschedulablePodException(Throwable cause) {
        super(cause);
    }

    public UnschedulablePodException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    public UnschedulablePodException() {
    }
}
