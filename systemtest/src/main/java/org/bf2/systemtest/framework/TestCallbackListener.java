package org.bf2.systemtest.framework;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.framework.matchers.Matchers;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.test.TestUtils;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * jUnit5 specific class which listening on test callbacks
 */
public class TestCallbackListener implements BeforeTestExecutionCallback, AfterTestExecutionCallback, BeforeAllCallback, BeforeEachCallback, AfterAllCallback, AfterEachCallback {
    private static final Logger LOGGER = LogManager.getLogger(TestCallbackListener.class);


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

    @Override
    public void beforeTestExecution(ExtensionContext extensionContext) throws Exception {
        extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).put(extensionContext.getDisplayName(), System.currentTimeMillis());
    }

    @Override
    public void afterTestExecution(ExtensionContext extensionContext) throws Exception {
        long startTime = extensionContext.getStore(ExtensionContext.Namespace.GLOBAL).remove(extensionContext.getDisplayName(), long.class);
        long duration = (System.currentTimeMillis() - startTime) / 1000;
        LOGGER.info("Test method {} took {} s.", extensionContext.getDisplayName(), duration);
        // this is workaround for surefire because if cant handle exceptions in afterTestExecutionCallback
        try {
            assertThat(FleetShardOperatorManager.getErrorsFromOperatorLog(duration), Matchers.logHasNoUnexpectedErrors());
        } catch (AssertionError ae) {
            LOGGER.error("Test failed at Test after test execution");
            try {
                LogCollector.saveKubernetesState(extensionContext, ae);
            } catch (Throwable throwable) {
                throw new Exception(throwable);
            }
        }
    }
}
