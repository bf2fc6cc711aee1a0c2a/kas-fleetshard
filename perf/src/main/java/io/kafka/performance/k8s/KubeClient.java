package io.kafka.performance.k8s;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction over fabric8 client
 */
public class KubeClient {

    protected final OpenShiftClient client;
    protected String namespace;

    public KubeClient(OpenShiftClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    /**
     * Returns fabric8 client for direc interaction
     * @return fabric8 client
     */
    public OpenShiftClient getClient() {
        return client;
    }

    /**
     * Set namespace of fabric8 client
     * @param futureNamespace namespace name
     * @return kubernetes client
     */
    public KubeClient namespace(String futureNamespace) {
        return new KubeClient(this.client, futureNamespace);
    }

    public String getNamespace() {
        return namespace;
    }

    public Namespace getNamespace(String namespace) {
        return client.namespaces().withName(namespace).get();
    }

    public void waitForCreateNamespace(String name) {
        createNamespace(name, Collections.emptyMap(), Collections.emptyMap());
        try {
            client.namespaces().withName(name).waitUntilCondition(Objects::nonNull, 600, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.interrupted();
            throw new RuntimeException(e);
        }
    }

    public void createNamespace(String name, Map<String, String> annotations, Map<String, String> labels) {
        if (client.namespaces().withName(name).get() != null) {
            waitForDeleteNamespace(name);
        }
        Namespace ns = new NamespaceBuilder().
                withNewMetadata()
                .withName(name)
                .withAnnotations(annotations.isEmpty() ? null : annotations)
                .withLabels(labels.isEmpty() ? null : labels)
                .endMetadata().build();
        client.namespaces().createOrReplace(ns);
    }

    public void waitForDeleteNamespace(String name) {
        if (getNamespace(name) != null) {
            client.namespaces().withName(name).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
            try {
                client.namespaces().withName(name).waitUntilCondition(Objects::isNull, 600, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.interrupted();
                throw new RuntimeException(e);
            }
        }
    }

    public List<Pod> listPods(Map<String, String> labelSelector) {
        return client.pods().inNamespace(getNamespace()).withLabels(labelSelector).list().getItems();
    }

    public List<Pod> listPods(String key, String value) {
        return listPods(Collections.singletonMap(key, value));
    }

    public List<Pod> listPods() {
        return client.pods().inNamespace(getNamespace()).list().getItems();
    }

    public Secret createSecret(Secret secret) {
        return client.secrets().inNamespace(getNamespace()).createOrReplace(secret);
    }

    public List<Node> listNodes() {
        return client.nodes().list().getItems();
    }

    public String logs(String podName, String containerName) {
        return client.pods().inNamespace(getNamespace()).withName(podName).inContainer(containerName).getLog();
    }

    public String terminatedLogs(String podName, String containerName) {
        return client.pods().inNamespace(getNamespace()).withName(podName).inContainer(containerName).terminated().getLog();
    }

    public void createOrReplaceService(String namespace, Service service) {
        client.services().inNamespace(namespace).createOrReplace(service);
    }

    public void createOrReplaceRoute(String namespace, Route route) {
        client.routes().inNamespace(namespace).createOrReplace(route);
    }

    public List<Container> getContainersFromPod(String podName) {
        Objects.requireNonNull(podName);
        return client.pods().inNamespace(getNamespace()).withName(podName).get().getSpec().getContainers();
    }

}
