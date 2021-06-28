package org.bf2.performance.framework;

import com.google.common.base.Strings;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.performance.Environment;
import org.bf2.test.TestUtils;
import org.bf2.test.executor.ExecBuilder;
import org.bf2.test.executor.ExecResult;
import org.bf2.test.k8s.KubeClient;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;
import org.gradle.api.UncheckedIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstraction around an openshift KubeClient for tracking and additional helper methods
 */
public class KubeClusterResource {

    private static final Logger LOGGER = LogManager.getLogger(KubeClusterResource.class);

    private static List<KubeClusterResource> kubernetesClusters = new LinkedList<>();

    private final String name;
    private KubeClient client;
    private final Set<String> scrappedNamespaces = new LinkedHashSet<>();

    public static synchronized KubeClusterResource connectToKubeCluster(String kubeconfig) {
        KubeClusterResource cluster = new KubeClusterResource(kubeconfig);
        kubernetesClusters.add(cluster);
        return cluster;
    }

    /**
     * Get current connected clusters
     * @return list of KubeClusterResources
     */
    public static synchronized List<KubeClusterResource> getCurrentConnectedClusters() {
        return kubernetesClusters;
    }

    /**
     * Delete all already connected clusters
     */
    public static synchronized void disconnectFromAllClusters() {
        kubernetesClusters.clear();
    }

    KubeClusterResource(String kubeconfig) {
        Path path = Paths.get(kubeconfig);
        this.name = path.getName(path.getNameCount() - 1).toString();
        try {
            client = new KubeClient(new DefaultOpenShiftClient(new OpenShiftConfig(getConfig(kubeconfig))), kubeconfig);
            LOGGER.info("Cluster {} default namespace is {}", kubeconfig, client.cmdClient().defaultNamespace());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Provides appropriate CMD client for running cluster
     *
     * @return CMD client
     */
    public KubeCmdClient<?> cmdKubeClient() {
        return client.cmdClient();
    }

    /**
     * Provides appropriate Kubernetes client for running cluster
     *
     * @return Kubernetes client
     */
    public KubeClient kubeClient() {
        return client;
    }

    /*
     * Returns true if Cluster availability is multizone
     */
    public boolean isMultiAZ() throws IOException {
        Set<String> zones = new HashSet<>();
        for (Node node : kubeClient().client().nodes().list().getItems()) {
            if (node.getMetadata().getLabels().containsKey("topology.kubernetes.io/zone")) {
                zones.add(node.getMetadata().getLabels().get("topology.kubernetes.io/zone"));
            }
        }
        return zones.size() > 1;
    }

    public String getName() {
        return name;
    }

    public void waitForDeleteNamespace(String name) {
        client.client().namespaces().withName(name).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
        TestUtils.waitFor("namespace delete", 1_000, 600_000, () -> client.client().namespaces().withName(name).get() == null);
    }

    public void createNamespace(String name, Map<String, String> annotations, Map<String, String> labels) {
        waitForDeleteNamespace(name);
        Namespace ns = new NamespaceBuilder().
                withNewMetadata()
                .withName(name)
                .withAnnotations(annotations.isEmpty() ? null : annotations)
                .withLabels(labels.isEmpty() ? null : labels)
                .endMetadata().build();
        client.client().namespaces().createOrReplace(ns);
    }

    Config getConfig(String kubeconfigPath) throws IOException {
        String content = Files.readString(Paths.get(kubeconfigPath));
        Config result = Config.fromKubeconfig(content);
        result.setConnectionTimeout(30_000);
        // TODO: enable with fabric8 5.5+
        //config.setRequestRetryBackoffInterval(1000);
        //config.setRequestRetryBackoffLimit(5);
        return result;
    }

    /**
     * Installs monitoring stack if not exists and connect namespace to scrape in case it is not already connected
     *
     * @param namespace namespace to scrape
     */
    public void connectNamespaceToMonitoringStack(String namespace) throws IOException {
        LOGGER.info("Installing monitoring stack and adding {} into stack", namespace);
        if ((Files.exists(Environment.MONITORING_STUFF_DIR) && !scrappedNamespaces.contains(namespace)) ||
                client.client().namespaces().withName("managed-services-monitoring-grafana").get() == null
                || client.client().namespaces().withName("managed-services-monitoring-prometheus").get() == null) {
            scrappedNamespaces.add(namespace);
            ExecResult res = new ExecBuilder()
                    .withCommand("make",
                            "--directory", Environment.MONITORING_STUFF_DIR.toString(), "install/monitoring/cluster")
                    .withEnvVars(Set.of(
                            new EnvVarBuilder().withName("KUBECONFIG").withValue(Environment.KAFKA_KUBECONFIG).build(),
                            new EnvVarBuilder().withName("PROMETHEUS_REMOTE_WRITE_URL").withValue(Environment.THANOS_URL).build(),
                            new EnvVarBuilder().withName("CLUSTER_ID").withValue("perf-test-cluster").build(),
                            new EnvVarBuilder().withName("METRICS_SCRAPE_NAMESPACES").withValue(String.join(",", scrappedNamespaces)).build(),
                            new EnvVarBuilder().withName("LOG_SCRAPE_NAMESPACES").withValue(String.join(",", scrappedNamespaces)).build()))
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
                                new EnvVarBuilder().withName("METRICS_SCRAPE_NAMESPACES").withValue(String.join(",", scrappedNamespaces)).build(),
                                new EnvVarBuilder().withName("LOG_SCRAPE_NAMESPACES").withValue(String.join(",", scrappedNamespaces)).build()))
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

}
