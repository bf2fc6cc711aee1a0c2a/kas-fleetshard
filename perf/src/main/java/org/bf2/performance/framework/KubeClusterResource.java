package org.bf2.performance.framework;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;
import org.gradle.api.UncheckedIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
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
        result.setRequestRetryBackoffInterval(1000);
        result.setRequestRetryBackoffLimit(5);
        return result;
    }

    /**
     * Installs monitoring stack if not exists and connect namespace to scrape in case it is not already connected
     *
     * @param namespace namespace to scrape
     */
    public void connectNamespaceToMonitoringStack(String namespace) throws IOException {
        LOGGER.info("TODO: Monitoring stack is not available for {}", namespace);
    }

}
