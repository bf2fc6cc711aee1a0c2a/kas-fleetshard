package io.kafka.performance;

import com.google.common.base.Strings;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.kafka.performance.k8s.KubeClusterResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.executor.ExecBuilder;
import org.bf2.test.executor.ExecResult;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Provides tools for connecting kafka clusters into openshift monitoring stack
 */
public class Monitoring {
    private static final Logger LOGGER = LogManager.getLogger(Monitoring.class);
    private static final List<String> SCRAPED_NAMESPACES = new LinkedList<>();

    /**
     * Installs monitoring stack if not exists and connect namespace to scrape in case it is not already connected
     *
     * @param namespace namespace to scrape
     */
    public static void connectNamespaceToMonitoringStack(KubeClusterResource cluster, String namespace) throws IOException {
        LOGGER.info("Installing monitoring stack and adding {} into stack", namespace);
        if ((Files.exists(Environment.MONITORING_STUFF_DIR) && !SCRAPED_NAMESPACES.contains(namespace)) ||
                cluster.kubeClient().getNamespace("managed-services-monitoring-grafana") == null || cluster.kubeClient().getNamespace("managed-services-monitoring-prometheus") == null) {
            addNamespace(namespace);
            ExecResult res = new ExecBuilder()
                    .withCommand("make",
                            "--directory", Environment.MONITORING_STUFF_DIR.toString(), "install/monitoring/cluster")
                    .withEnvVars(Set.of(
                            new EnvVarBuilder().withName("KUBECONFIG").withValue(Environment.KAFKA_KUBECONFIG).build(),
                            new EnvVarBuilder().withName("PROMETHEUS_REMOTE_WRITE_URL").withValue(Environment.THANOS_URL).build(),
                            new EnvVarBuilder().withName("CLUSTER_ID").withValue("perf-test-cluster").build(),
                            new EnvVarBuilder().withName("METRICS_SCRAPE_NAMESPACES").withValue(String.join(",", SCRAPED_NAMESPACES)).build(),
                            new EnvVarBuilder().withName("LOG_SCRAPE_NAMESPACES").withValue(String.join(",", SCRAPED_NAMESPACES)).build()))
                    .logToOutput(false)
                    .exec();
            if (!res.exitStatus()) {
                LOGGER.warn(res.out());
                LOGGER.warn(res.err());
            }
            if (!Strings.isNullOrEmpty(Environment.OBSERVATORIUM_ROUTE)) {
                LOGGER.info("Adding namespace {} into monitoring stack", namespace);
                res = new ExecBuilder()
                        .withCommand("make",
                                "--directory", Environment.MONITORING_STUFF_DIR.toString(), "setup/observatorium")
                        .withEnvVars(Set.of(
                                new EnvVarBuilder().withName("KUBECONFIG").withValue(Environment.KAFKA_KUBECONFIG).build(),
                                new EnvVarBuilder().withName("OBSERVATORIUM_APPS_URL").withValue(Environment.OBSERVATORIUM_ROUTE).build(),
                                new EnvVarBuilder().withName("CLUSTER_ID").withValue("perf-test-cluster").build(),
                                new EnvVarBuilder().withName("METRICS_SCRAPE_NAMESPACES").withValue(String.join(",", SCRAPED_NAMESPACES)).build(),
                                new EnvVarBuilder().withName("LOG_SCRAPE_NAMESPACES").withValue(String.join(",", SCRAPED_NAMESPACES)).build()))
                        .logToOutput(false)
                        .exec();
                if (!res.exitStatus()) {
                    LOGGER.warn(res.out());
                    LOGGER.warn(res.err());
                }
            }
        } else {
            LOGGER.warn("kafka-monitoring-stuff is not present or namespace is already connected, nothing to do!");
        }
    }

    private static void addNamespace(String namespace) {
        if (!SCRAPED_NAMESPACES.contains(namespace)) {
            SCRAPED_NAMESPACES.add(namespace);
        }
    }
}
