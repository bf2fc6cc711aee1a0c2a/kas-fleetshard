package org.bf2.common;

import org.jboss.logmanager.Level;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.MalformedURLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LoggingConfigWatcherTest {

    @Test
    public void testConfigParsing() throws MalformedURLException, IOException {
        LoggingConfigWatcher
                .updateLoggingConfig(LoggingConfigWatcher.class.getResource("/loggingconfig/application.properties"));

        LogContext logContext = LogContext.getLogContext();
        org.jboss.logmanager.Logger logger = logContext.getLogger("some.category");
        assertEquals(Level.DEBUG, logger.getLevel());

        logger = logContext.getLogger("some.other.category");
        assertEquals(Level.WARNING, logger.getLevel());
    }

}
