package io.kafka.performance.framework;

import org.junit.jupiter.api.extension.ExtensionContext;

public class ActualTestMetadata {
    private ExtensionContext metadata;
    private static ActualTestMetadata instance;

    private ActualTestMetadata() {
    }

    public static synchronized ActualTestMetadata getInstance() {
        if (instance == null) {
            instance = new ActualTestMetadata();
        }
        return instance;
    }

    public void setMetadata(ExtensionContext metadata) {
        this.metadata = metadata;
    }

    public ExtensionContext getMetadata() {
        return metadata;
    }

    public String getDisplayName() {
        return instance.metadata.getDisplayName();
    }
}
