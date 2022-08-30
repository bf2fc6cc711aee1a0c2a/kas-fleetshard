package org.bf2.test;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Test utils contains static help methods
 */
public class TestUtils {
    private static final Logger LOGGER = LogManager.getLogger(TestUtils.class);

    public static void waitFor(String description, long pollIntervalMs, long timeoutMs, BooleanSupplier ready) {
        try {
            asyncWaitFor(description, pollIntervalMs, timeoutMs, ready).get();
        } catch (InterruptedException e) {
            Thread.interrupted();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException)e.getCause();
            }
            throw new RuntimeException(e);
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

    public static CompletableFuture<Void> asyncWaitFor(String description, long pollIntervalMs, long timeoutMs, BooleanSupplier ready) {
        LOGGER.info("Waiting for {}", description);
        long deadline = System.currentTimeMillis() + timeoutMs;
        CompletableFuture<Void> future = new CompletableFuture<>();
        Executor delayed = CompletableFuture.delayedExecutor(pollIntervalMs, TimeUnit.MILLISECONDS, EXECUTOR);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                boolean result;
                try {
                    result = ready.getAsBoolean();
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    return;
                }
                long timeLeft = deadline - System.currentTimeMillis();
                if (!future.isDone()) {
                    if (!result) {
                        if (timeLeft >= 0) {
                            if (LOGGER.isTraceEnabled()) {
                                LOGGER.trace("{} not ready, will try again ({}ms till timeout)", description, timeLeft);
                            }
                            delayed.execute(this);
                        } else {
                            future.completeExceptionally(new TimeoutException(String.format("Waiting for %s timeout %s exceeded", description, timeoutMs)));
                        }
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
        String testClassName = context.getTestClass().map(Class::getName).orElse("NOCLASS");
        return getLogPath(folderName, testClassName, testMethod);
    }

    public static Path getLogPath(String folderName, TestInfo info) {
        String testMethod = info.getDisplayName();
        String testClassName = info.getTestClass().map(Class::getName).orElse("NOCLASS");
        return getLogPath(folderName, testClassName, testMethod);
    }

    public static Path getLogPath(String folderName, String testClassName, String testMethod) {
        Path path = Environment.LOG_DIR.resolve(Paths.get(folderName, testClassName));
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
     * Return true if the resource isReady. Will be false if null.
     * @param resource to check
     */
    public static boolean isReady(Resource<? extends HasMetadata> resource) {
        HasMetadata meta = resource.get();
        if (meta == null) {
            return false;
        }
        return Readiness.getInstance().isReady(meta);
    }

    public static boolean isPodReady(Pod p) {
        if (p != null) {
            return p.getStatus().getContainerStatuses().stream().allMatch(ContainerStatus::getReady);
        }
        return false;
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
