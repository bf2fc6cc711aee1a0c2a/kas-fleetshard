package org.bf2.test.k8s;

import io.fabric8.kubernetes.api.model.APIService;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;
import org.bf2.test.k8s.cmdClient.Kubectl;
import org.bf2.test.k8s.cmdClient.Oc;

import java.util.List;

public class KubeClient {
    private static final Logger LOGGER = LogManager.getLogger(KubeClient.class);

    @SuppressWarnings("rawtypes")
    private final KubeCmdClient cmdClient;
    private final KubernetesClient client;
    private static KubeClient instance;


    private KubeClient() {
        this.client = new DefaultKubernetesClient();
        if (isGenericKubernetes()) {
            LOGGER.info("Running tests against generic kubernetes cluster");
            this.cmdClient = new Kubectl();
        } else {
            LOGGER.info("Running tests against openshift cluster");
            this.cmdClient = new Oc();
        }
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

    private boolean isGenericKubernetes() {
        List<APIService> services = new DefaultKubernetesClient().apiServices().list().getItems();
        for (APIService apiService : services) {
            if (apiService.getMetadata().getName().contains("openshift.io")) {
                return false;
            }
        }
        return true;
    }
}
