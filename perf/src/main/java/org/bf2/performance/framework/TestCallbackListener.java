package org.bf2.performance.framework;

import org.bf2.performance.PerformanceEnvironment;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * jUnit5 specific class which listening on test callbacks
 */
public class TestCallbackListener implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback, AfterEachCallback {

    static {
        PerformanceEnvironment.logEnvironment();
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        org.bf2.test.TestUtils.logWithSeparator("-> Running test class: {}", extensionContext.getRequiredTestClass().getName());
        TestMetadataCapture.getInstance().cleanOpenshiftData();
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws Exception {
        org.bf2.test.TestUtils.logWithSeparator("-> Running test method: {}", extensionContext.getDisplayName());
        ActualTestMetadata.getInstance().setMetadata(extensionContext);
        TestMetadataCapture.getInstance().cleanKafkaOmbData();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        KubeClusterResource.disconnectFromAllClusters();
        org.bf2.test.TestUtils.logWithSeparator("-> End of test class: {}", extensionContext.getRequiredTestClass().getName());
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        org.bf2.test.TestUtils.logWithSeparator("-> End of test method: {}", extensionContext.getDisplayName());
    }
}
