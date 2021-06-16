package io.kafka.performance.k8s;

import io.fabric8.kubernetes.api.model.Node;
import io.kafka.performance.k8s.cluster.KubeCluster;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Abstraction for connected kubernetes/openshift cluster
 */
public class KubeClusterResource {

    private static final Logger LOGGER = LogManager.getLogger(KubeClusterResource.class);

    private final String name;
    private KubeCluster kubeCluster;
    private KubeCmdClient cmdClient;
    private KubeClient client;

    private String namespace;
    private String testNamespace;

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
            LOGGER.info("Cluster command line client default namespace is {}", getTestNamespace());
        } catch (RuntimeException | IOException e) {
            Assumptions.assumeTrue(false, e.getMessage());
        }
    }

    private void initNamespaces() {
        setDefaultNamespace(cmdKubeClient().defaultNamespace());
        setTestNamespace(cmdKubeClient().defaultNamespace());
    }

    public void setTestNamespace(String testNamespace) {
        this.testNamespace = testNamespace;
    }

    public void setDefaultNamespace(String namespace) {
        this.namespace = namespace;
    }

    /**
     * Sets the namespace value for Kubernetes clients
     *
     * @param futureNamespace Namespace which should be used in Kubernetes clients
     * @return Previous namespace which was used in Kubernetes clients
     */
    public String setNamespace(String futureNamespace) {
        String previousNamespace = namespace;
        LOGGER.info("Changing to {} namespace", futureNamespace);
        namespace = futureNamespace;
        return previousNamespace;
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
        return client.namespace(getNamespace());
    }

    /**
     * Provides appropriate Kubernetes client with expected namespace for running cluster
     *
     * @param inNamespace Namespace will be used as a current namespace for client
     * @return Kubernetes client with expected namespace in configuration
     */
    public KubeClient kubeClient(String inNamespace) throws IOException {
        return client.namespace(inNamespace);
    }

    /**
     * Create namespaces for test resources.
     *
     * @param useNamespace namespace which will be used as default by kubernetes client
     * @param namespaces   list of namespaces which will be created
     */
    public void createNamespaces(String useNamespace, List<String> namespaces) throws IOException {
        bindingsNamespaces = namespaces;
        for (String namespace : namespaces) {

            if (kubeClient().getNamespace(namespace) != null) {
                LOGGER.warn("Namespace {} is already created, going to delete it", namespace);
                kubeClient().waitForDeleteNamespace(namespace);
            }

            LOGGER.info("Creating Namespace {}", namespace);
            deploymentNamespaces.add(namespace);
            kubeClient().waitForCreateNamespace(namespace);
        }
        testNamespace = useNamespace;
        LOGGER.info("Using Namespace {}", useNamespace);
        setNamespace(useNamespace);
    }

    /**
     * Create namespace for test resources. Deletion is up to caller and can be managed
     * by calling {@link #deleteNamespaces()}
     *
     * @param useNamespace namespace which will be created and used as default by kubernetes client
     */
    public void createNamespace(String useNamespace) throws IOException {
        createNamespaces(useNamespace, Collections.singletonList(useNamespace));
    }

    /**
     * Delete all created namespaces. Namespaces are deleted in the reverse order than they were created.
     */
    public void deleteNamespaces() throws IOException {
        Collections.reverse(deploymentNamespaces);
        for (String namespace : deploymentNamespaces) {
            LOGGER.info("Deleting Namespace {}", namespace);
            kubeClient().waitForDeleteNamespace(namespace);
        }
        deploymentNamespaces.clear();
        bindingsNamespaces = null;
        LOGGER.info("Using Namespace {}", testNamespace);
        setNamespace(testNamespace);
    }

    /*
     * Returns true if Cluster availability is multizone
     */
    public boolean isMultiAZ() throws IOException {
        Set<String> zones = new HashSet<>();
        for (Node node : kubeClient().getClient().nodes().list().getItems()) {
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

    public String getTestNamespace() {
        return testNamespace;
    }

    public String getName() {
        return name;
    }
}
