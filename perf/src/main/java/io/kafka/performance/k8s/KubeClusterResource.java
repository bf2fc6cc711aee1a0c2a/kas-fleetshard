package io.kafka.performance.k8s;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.k8s.KubeClient;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
    private KubeCmdClient<?> cmdClient;
    private KubeClient client;

    private String namespace;

    protected List<String> bindingsNamespaces = new ArrayList<>();
    private final List<String> deploymentNamespaces = new ArrayList<>();

    public KubeClusterResource(String kubeconfig) {
        Path path = Paths.get(kubeconfig);
        this.name = path.getName(path.getNameCount() - 1).toString();
        try {
            kubeCluster = KubeCluster.bootstrap();
            client = kubeCluster.defaultClient(kubeconfig);
            cmdClient = kubeCluster.defaultCmdClient(kubeconfig, client);
            initNamespaces();
            LOGGER.info("Cluster default namespace is {}", getNamespace());
            LOGGER.info("Cluster command line client default namespace is {}", getNamespace());
        } catch (RuntimeException | IOException e) {
            Assumptions.assumeTrue(false, e.getMessage());
        }
    }

    private void initNamespaces() {
        this.namespace=cmdKubeClient().defaultNamespace();
    }

    public List<String> getBindingsNamespaces() {
        return bindingsNamespaces;
    }

    /**
     * Gets namespace which is used in Kubernetes clients at the moment
     *
     * @return Used namespace
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Provides appropriate CMD client for running cluster
     *
     * @return CMD client
     */
    public KubeCmdClient<?> cmdKubeClient() {
        return cmdClient.namespace(getNamespace());
    }

    /**
     * Provides appropriate CMD client with expected namespace for running cluster
     *
     * @param inNamespace Namespace will be used as a current namespace for client
     * @return CMD client with expected namespace in configuration
     */
    public KubeCmdClient<?> cmdKubeClient(String inNamespace) {
        return cmdClient.namespace(inNamespace);
    }

    /**
     * Provides appropriate Kubernetes client for running cluster
     *
     * @return Kubernetes client
     */
    public KubeClient kubeClient() {
        return client;
    }

    /**
     * Delete all created namespaces. Namespaces are deleted in the reverse order than they were created.
     */
    public void deleteNamespaces() throws IOException {
        Collections.reverse(deploymentNamespaces);
        for (String namespace : deploymentNamespaces) {
            LOGGER.info("Deleting Namespace {}", namespace);
            waitForDeleteNamespace(namespace);
        }
        deploymentNamespaces.clear();
        bindingsNamespaces = null;
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

    /**
     * Gets the namespace in use
     */
    public String defaultNamespace() {
        return cmdClient.defaultNamespace();
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
        if (client.client().namespaces().withName(name).get() != null) {
            waitForDeleteNamespace(name);
        }
        Namespace ns = new NamespaceBuilder().
                withNewMetadata()
                .withName(name)
                .withAnnotations(annotations.isEmpty() ? null : annotations)
                .withLabels(labels.isEmpty() ? null : labels)
                .endMetadata().build();
        client.client().namespaces().createOrReplace(ns);
    }

}
