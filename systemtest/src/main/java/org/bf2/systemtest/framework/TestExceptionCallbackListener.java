package org.bf2.systemtest.framework;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * jUnit5 specific class which listening on test exception callbacks
 */
public class TestExceptionCallbackListener
        implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
    private static final Logger LOGGER = LogManager.getLogger(TestExceptionCallbackListener.class);

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        LOGGER.error("Test failed at {} : {}", "Test execution", throwable.getMessage(), throwable);
        LogCollector.saveKubernetesState(context, throwable);
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        LOGGER.error("Test failed at {} : {}", "Test before all", throwable.getMessage(), throwable);
        LogCollector.saveKubernetesState(context, throwable);
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        LOGGER.error("Test failed at {} : {}", "Test before each", throwable.getMessage(), throwable);
        LogCollector.saveKubernetesState(context, throwable);
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        LOGGER.error("Test failed at {} : {}", "Test after each", throwable.getMessage(), throwable);
        LogCollector.saveKubernetesState(context, throwable);
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        LOGGER.error("Test failed at {} : {}", "Test after all", throwable.getMessage(), throwable);
        LogCollector.saveKubernetesState(context, throwable);
    }
}
