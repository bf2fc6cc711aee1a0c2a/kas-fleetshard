package org.bf2.systemtest.framework;

import org.bf2.test.TestUtils;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * jUnit5 specific class which listening on test callbacks
 */
public class TestCallbackListener implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback, AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        TestUtils.logWithSeparator("-> Running test class: {}", extensionContext.getRequiredTestClass().getName());
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws Exception {
        TestUtils.logWithSeparator("-> Running test method: {}", extensionContext.getDisplayName());
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        TestUtils.logWithSeparator("-> End of test class: {}", extensionContext.getRequiredTestClass().getName());
        ResourceManager.getInstance().deleteResources(extensionContext);
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        TestUtils.logWithSeparator("-> End of test method: {}", extensionContext.getDisplayName());
        ResourceManager.getInstance().deleteResources(extensionContext);
    }
}
