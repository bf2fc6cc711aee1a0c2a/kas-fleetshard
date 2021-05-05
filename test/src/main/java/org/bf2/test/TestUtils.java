package org.bf2.test;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.IOHelpers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.k8s.KubeClient;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Test utils contains static help methods
 */
public class TestUtils {
    private static final Logger LOGGER = LogManager.getLogger(TestUtils.class);

    public static long waitFor(String description, long pollIntervalMs, long timeoutMs, BooleanSupplier ready) {
        return waitFor(description, pollIntervalMs, timeoutMs, ready, () -> {
        });
    }

    /**
     * Wait for specific lambda expression
     *
     * @param description    description for logging
     * @param pollIntervalMs poll interval in ms
     * @param timeoutMs      timeout in ms
     * @param ready          lambda method for waiting
     * @param onTimeout      lambda method which is called when timeout is reached
     * @return
     */
    public static long waitFor(String description, long pollIntervalMs, long timeoutMs, BooleanSupplier ready, Runnable onTimeout) {
        LOGGER.debug("Waiting for {}", description);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            boolean result;
            try {
                result = ready.getAsBoolean();
            } catch (Exception e) {
                result = false;
            }
            long timeLeft = deadline - System.currentTimeMillis();
            if (result) {
                return timeLeft;
            }
            if (timeLeft <= 0) {
                onTimeout.run();
                WaitException waitException = new WaitException("Timeout after " + timeoutMs + " ms waiting for " + description);
                waitException.printStackTrace();
                throw waitException;
            }
            long sleepTime = Math.min(pollIntervalMs, timeLeft);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("{} not ready, will try again in {} ms ({}ms till timeout)", description, sleepTime, timeLeft);
            }
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                return deadline - System.currentTimeMillis();
            }
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        final ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();

        @Override
        public Thread newThread(Runnable r) {
            Thread result = defaultThreadFactory.newThread(r);
            result.setDaemon(true);
            return result;
        }
    });

    public static Future<Void> asyncWaitFor(String description, long pollIntervalMs, BooleanSupplier ready) {
        LOGGER.debug("Waiting for {}", description);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Executor delayed = CompletableFuture.delayedExecutor(pollIntervalMs, TimeUnit.MILLISECONDS, EXECUTOR);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                boolean result;
                try {
                    result = ready.getAsBoolean();
                } catch (Exception e) {
                    result = false;
                }
                if (!future.isDone()) {
                    if (!result) {
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("{} not ready, will try again in {} ms", description, pollIntervalMs);
                        }
                        delayed.execute(this);
                    } else {
                        future.complete(null);
                    }
                }
            }
        };
        r.run();
        return future;
    }

    public static Path getLogPath(String folderName, ExtensionContext context) {
        String testMethod = context.getDisplayName();
        Class<?> testClass = context.getTestClass().get();
        return getLogPath(folderName, testClass, testMethod);
    }

    public static Path getLogPath(String folderName, TestInfo info) {
        String testMethod = info.getDisplayName();
        Class<?> testClass = info.getTestClass().get();
        return getLogPath(folderName, testClass, testMethod);
    }

    public static Path getLogPath(String folderName, Class<?> testClass, String testMethod) {
        Path path = Environment.LOG_DIR.resolve(Paths.get(folderName, testClass.getName()));
        if (testMethod != null) {
            path = path.resolve(testMethod.replace("(", "").replace(")", ""));
        }
        return path;
    }

    public static void logWithSeparator(String pattern, String text) {
        LOGGER.info("=======================================================================");
        LOGGER.info(pattern, text);
        LOGGER.info("=======================================================================");
    }

    /**
     * Check all containers state in pod and return boolean status if po is running
     *
     * @param p pod
     */
    public static boolean isPodReady(Pod p) {
        return p.getStatus().getContainerStatuses().stream().allMatch(ContainerStatus::getReady);
    }

    /**
     * Replacer function replacing values in input stream and returns modified input stream
     *
     * @param values map of values for replace
     */
    public static Function<InputStream, InputStream> replacer(final Map<String, String> values) {
        return in -> {
            try {
                String strContent = IOHelpers.readFully(in);
                for (Map.Entry<String, String> replacer : values.entrySet()) {
                    strContent = strContent.replace(replacer.getKey(), replacer.getValue());
                }
                return new ByteArrayInputStream(strContent.getBytes());
            } catch (IOException ex) {
                throw KubernetesClientException.launderThrowable(ex);
            }
        };
    }

    /**
     * Restart kubeapi
     */
    public static void restartKubeApi() {
        final String apiNamespace = KubeClient.getInstance().isGenericKubernetes() ? "kube-system" : "openshift-kube-apiserver";

        LOGGER.info("Restarting kubeapi");
        for (int i = 0; i < 60; i++) {
            if (!KubeClient.getInstance().isGenericKubernetes()) {
                KubeClient.getInstance().client().pods().inNamespace("openshift-apiserver").delete();
            }
            KubeClient.getInstance().client().pods().inNamespace(apiNamespace).list().getItems().stream().filter(pod ->
                    pod.getMetadata().getName().contains("kube-apiserver-")).forEach(pod ->
                    KubeClient.getInstance().client().pods().inNamespace(apiNamespace).withName(pod.getMetadata().getName()).withGracePeriod(1000).delete());
        }
    }
}
