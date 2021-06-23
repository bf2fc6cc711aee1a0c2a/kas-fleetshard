package org.bf2.performance.k8s;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.k8s.KubeClient;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;
import org.gradle.api.UncheckedIOException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction for connected kubernetes/openshift cluster
 */
public class KubeClusterResource {

    private static final Logger LOGGER = LogManager.getLogger(KubeClusterResource.class);

    private final String name;
    private KubeCluster kubeCluster;
    private KubeClient client;

    public KubeClusterResource(String kubeconfig) {
        Path path = Paths.get(kubeconfig);
        this.name = path.getName(path.getNameCount() - 1).toString();
        try {
            kubeCluster = KubeCluster.bootstrap();
            client = kubeCluster.defaultClient(kubeconfig);
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
        try {
            client.client().namespaces().withName(name).waitUntilCondition(Objects::isNull, 600, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
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

}
