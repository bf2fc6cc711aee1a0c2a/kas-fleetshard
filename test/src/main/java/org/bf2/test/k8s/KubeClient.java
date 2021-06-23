package org.bf2.test.k8s;

import com.google.common.base.Functions;
import io.fabric8.kubernetes.api.model.APIService;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;
import org.bf2.test.k8s.cmdClient.Kubectl;
import org.bf2.test.k8s.cmdClient.Oc;
import org.gradle.api.UncheckedIOException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Abstraction over fabric8 client and cmd kube client
 */
public class KubeClient {
    private static final Logger LOGGER = LogManager.getLogger(KubeClient.class);

    @SuppressWarnings("rawtypes")
    private final KubeCmdClient cmdClient;
    private final KubernetesClient client;
    private static KubeClient instance;

    public KubeClient(KubernetesClient client, String kubeConfig) {
        this.client = client;
        if (isGenericKubernetes()) {
            LOGGER.info("Running tests against generic kubernetes cluster");
            this.cmdClient = new Kubectl();
        } else {
            LOGGER.info("Running tests against openshift cluster");
            this.cmdClient = new Oc(kubeConfig);
        }
    }

    private KubeClient() {
        this(new DefaultKubernetesClient(), null);
    }

    /**
     * Return singleton of kube client
     * contains kubernetes client and cmd kube client
     *
     * Works only if your local kubectl context is already setup, otherwise
     * you need to use an instance created with a specific config - usage from
     * performance tests for example is problematic
     *
     * @return
     */
    public static synchronized KubeClient getInstance() {
        if (instance == null) {
            instance = new KubeClient();
        }
        return instance;
    }

    public KubernetesClient client() {
        return this.client;
    }

    @SuppressWarnings("rawtypes")
    public KubeCmdClient cmdClient() {
        return this.cmdClient;
    }

    public boolean isGenericKubernetes() {
        List<APIService> services = this.client.apiServices().list().getItems();
        for (APIService apiService : services) {
            if (apiService.getMetadata().getName().contains("openshift.io")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Apply resources from files
     *
     * @param namespace namessppace where to apply
     * @param paths     folders
     */
    public void apply(String namespace, InputStream is, Function<HasMetadata, HasMetadata> modifier) throws IOException {
        try (is) {
            client.load(is).get().stream().forEach(i -> {
                HasMetadata h = modifier.apply(i);
                if (h != null) {
                    client.resource(h).inNamespace(namespace).createOrReplace();
                }
            });
        }
    }

    /**
     * Namespace exists?
     */
    public boolean namespaceExists(String namespace) {
        return client.namespaces().withName(namespace).get() != null;
    }

    /**
     * Apply resources from a non-directory file
     *
     * @param namespace namesspace where to apply
     */
    public void apply(String namespace, Path path) {
        try {
            apply(namespace, Files.newInputStream(path), Functions.identity());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
