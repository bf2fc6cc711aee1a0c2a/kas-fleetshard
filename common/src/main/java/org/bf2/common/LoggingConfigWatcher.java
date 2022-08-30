package org.bf2.common;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logmanager.LogContext;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
@Startup
public class LoggingConfigWatcher {

    static final Pattern KEY_PATTERN = Pattern.compile("quarkus\\.log\\.category\\.[\"]?([^\"]*)[\"]?\\.level");

    private static Logger log = Logger.getLogger(LoggingConfigWatcher.class);

    @ConfigProperty(name = "logging.config.file", defaultValue = "/config/application.properties")
    String loggingConfigFile;
    @ConfigProperty(name = "logging.config.interval", defaultValue = "15s")
    Duration interval;

    private  volatile ScheduledExecutorService workerPool;
    private  volatile FileTime lastUpdated;

    void onStart(@Observes StartupEvent ev) throws IOException {
        Path path = Paths.get(loggingConfigFile);
        Path parentPath = path.getParent();

        if (parentPath == null || !Files.exists(parentPath)) {
            log.infof("Config directory %s does not exist, will not watch for changes", parentPath);
            return;
        }

        workerPool = Executors.newScheduledThreadPool(1);

        workerPool.scheduleAtFixedRate(() -> {
            if (!Files.exists(path)) {
                return;
            }
            try {
                FileTime time = Files.getLastModifiedTime(path);
                if (lastUpdated == null || time.compareTo(lastUpdated) > 0) {
                    lastUpdated = time;
                    updateLoggingConfig(path.toUri().toURL());
                }
            } catch (Exception e) {
                log.warnf(e, "Please check %s file - it cannot be processed", loggingConfigFile);
            }
        }, interval.getSeconds(), interval.getSeconds(), TimeUnit.SECONDS);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (workerPool != null) {
            workerPool.shutdown();
        }
    }

    static void updateLoggingConfig(URL url) throws MalformedURLException, IOException {
        PropertiesConfigSource pcs = new PropertiesConfigSource(url);
        pcs.getProperties().forEach((k, v) -> {
            Matcher matcher = KEY_PATTERN.matcher(k);
            if (matcher.matches()) {
                updateLogLevel(matcher.group(1), v);
            }
        });
    }

    /**
     * Taken from https://github.com/quarkusio/quarkus/blob/9861d4f0ccf61ed1fea4f067c309266ce07b0610/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/logstream/LogController.java#L78
     * rather than introducing a full dependency
     * @param loggerName
     * @param levelValue
     */
    public static void updateLogLevel(String loggerName, String levelValue) {
        LogContext logContext = LogContext.getLogContext();
        org.jboss.logmanager.Logger logger = logContext.getLogger(loggerName);
        if (logger != null) {
            java.util.logging.Level level = Level.parse(levelValue);
            logger.setLevel(level);
            log.infof("Log level updated [%s] changed to [%s]", loggerName, levelValue);
        }
    }

}
