package org.bf2.systemtest.k8s;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.bf2.systemtest.k8s.cmdClient.KubeCmdClient;
import org.bf2.systemtest.k8s.cmdClient.Kubectl;

public class KubeClient {
    @SuppressWarnings("rawtypes")
    private final KubeCmdClient cmdClient;
    private final KubernetesClient client;
    private static KubeClient instance;


    private KubeClient() {
        this.client = new DefaultKubernetesClient();
        this.cmdClient = new Kubectl();
    }

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
}
