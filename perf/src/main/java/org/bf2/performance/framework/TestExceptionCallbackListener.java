package org.bf2.performance.framework;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.performance.Constants;
import org.bf2.performance.Environment;
import org.bf2.performance.TestUtils;
import org.bf2.performance.k8s.KubeClusterResource;
import org.bf2.test.k8s.KubeClient;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Junit5 specific class which listening on test exception callbacks
 */
public class TestExceptionCallbackListener implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler, AfterTestExecutionCallback {
    private static final Logger LOGGER = LogManager.getLogger(TestExceptionCallbackListener.class);

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test execution", context, throwable);
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test before all", context, throwable);
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test before each", context, throwable);
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test after each", context, throwable);
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        saveKubernetesState("Test after all", context, throwable);
    }

    /**
     * Calls storing cluster info for every connected cluster
     *
     * @param description
     * @param extensionContext
     * @param throwable
     * @throws Throwable
     */
    private void saveKubernetesState(String description, ExtensionContext extensionContext, Throwable throwable) throws Throwable {
        Path logPath = TestUtils.getLogPath(Environment.LOG_DIR.resolve("failed-test").toString(), extensionContext);
        LOGGER.error("Test failed at {} : {}", description, throwable.getMessage(), throwable);
        LOGGER.info("Storing cluster info into {}", logPath.toString());
        for (KubeClusterResource kubeClusterResource : ClusterConnectionFactory.getCurrentConnectedClusters()) {
            try {
                storeClusterInfo(kubeClusterResource, logPath.resolve(kubeClusterResource.getName()));
            } catch (IOException e) {
                LOGGER.error("Cannot save logs from cluster {}", kubeClusterResource.kubeClient().client().getConfiguration().getMasterUrl(), e);
            }
        }
        throw throwable;
    }

    /**
     * Stores cluster specific information in case of failed test in test callback
     *
     * @param cluster
     * @param logPath
     * @throws IOException
     */
    private void storeClusterInfo(KubeClusterResource cluster, Path logPath) throws IOException {
        Files.createDirectories(logPath);
        LOGGER.info("Storing cluster info for {}", cluster.kubeClient().client().getConfiguration().getMasterUrl());
        Files.writeString(logPath.resolve("describe_cluster.log"), cluster.cmdKubeClient().exec(false, false, "describe", "nodes").out());
        Files.writeString(logPath.resolve("events.log"), cluster.cmdKubeClient().exec(false, false, "get", "events", "--all-namespaces").out());

        ExecutorService executorService = Executors.newFixedThreadPool(4);
        try {
            KubeClient kubeClient = cluster.kubeClient();
            cluster.kubeClient().client().namespaces().list().getItems().stream().filter(ns -> checkAnnotation(ns, Constants.IO_KAFKA_PERFORMANCE_COLLECTPODLOG)).forEach(ns -> {
                try {
                    Files.writeString(logPath.resolve(String.format("describe_%s_pods.log", ns.getMetadata().getName())), cluster.cmdKubeClient().exec(false, false, "describe", "pods", "-n", ns.getMetadata().getName()).out());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                NonNamespaceOperation<Pod, PodList, PodResource<Pod>> podsOp = kubeClient.client().pods().inNamespace(ns.getMetadata().getName());
                List<Pod> pods = podsOp.list().getItems();
                for (Pod p : pods) {
                    try {
                        List<Container> containers = podsOp.withName(p.getMetadata().getName()).get().getSpec().getContainers();
                        for (Container c : containers) {
                            executorService.submit(() -> {
                                Path filePath = logPath.resolve(String.format("%s_%s.log", p.getMetadata().getName(), c.getName()));
                                try {
                                    Files.writeString(filePath, podsOp.withName(p.getMetadata().getName()).inContainer(c.getName()).getLog());
                                } catch (IOException e) {
                                    LOGGER.warn("Cannot write file {}", filePath, e);
                                }
                            });
                        }
                    } catch (Exception ex) {
                        LOGGER.warn("Cannot access logs from pod {} ", p.getMetadata().getName(), ex);
                    }

                    p.getStatus().getContainerStatuses().stream().filter(cs -> cs.getRestartCount() > 0).forEach(cs -> {
                        executorService.submit(() -> {
                            Path filePath = logPath.resolve(String.format("%s_%s_terminated.log", p.getMetadata().getName(), cs.getName()));
                            try {
                                Files.writeString(filePath, podsOp.withName(p.getMetadata().getName()).inContainer(cs.getName()).terminated().getLog());
                            } catch (IOException e) {
                                LOGGER.warn("Cannot write file {}", filePath, e);
                            }
                        });
                    });
                }
            });
        } finally {
            executorService.shutdown();
            try {
                executorService.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        for (KubeClusterResource kubeClusterResource : ClusterConnectionFactory.getCurrentConnectedClusters()) {

            KubeClient kubeClient = kubeClusterResource.kubeClient();
            kubeClient.client().namespaces().list().getItems().stream().filter(ns -> checkAnnotation(ns, Constants.IO_KAFKA_PERFORMANCE_CHECKRESTARTEDCONTAINERS)).forEach(ns -> {

                List<Pod> podsWithRestartedContainers = kubeClient.client()
                        .pods()
                        .inNamespace(ns.getMetadata().getName())
                        .list()
                        .getItems()
                        .stream()
                        .filter(p -> p.getStatus()
                                .getContainerStatuses()
                                .stream()
                                .anyMatch(cs -> cs.getRestartCount() > 0))
                        .collect(Collectors.toList());
                if (!podsWithRestartedContainers.isEmpty()) {

                    LOGGER.error("Found {} pod(s) with containers that had restarted at least once", podsWithRestartedContainers.size());
                    podsWithRestartedContainers.forEach(p -> {
                        p.getStatus().getContainerStatuses().stream().filter(cs -> cs.getRestartCount() > 0).forEach(cs -> {
                            LOGGER.error("Pod {} container {} restart count {}", p.getMetadata().getName(), cs.getName(), cs.getRestartCount());
                        });
                    });

                    if (context.getExecutionException().isEmpty()) {
                        // Fail the test
                        throw new RuntimeException("Found {} pod(s) with containers that had restarted at least once");
                    }
                }
            });
        }

    }

    private boolean checkAnnotation(io.fabric8.kubernetes.api.model.Namespace ns, String name) {
        return ns.getMetadata().getAnnotations() != null && Boolean.parseBoolean(ns.getMetadata().getAnnotations().get(name));
    }
}
