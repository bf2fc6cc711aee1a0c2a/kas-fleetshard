package org.bf2.performance.k8s;

import io.fabric8.kubernetes.client.Config;
import org.bf2.test.k8s.KubeClient;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public interface KubeCluster {

    /**
     * Return a default CMD cmdClient for this kind of cluster.
     * @param client
     */
    KubeCmdClient<?> defaultCmdClient(String kubeconfig, KubeClient client);

    KubeClient defaultClient(String kubeconfig) throws IOException;

    /**
     * Returns the cluster named by the TEST_CLUSTER environment variable, if set, otherwise finds a cluster that's
     * both installed and running.
     *
     * @return The cluster.
     */
    static KubeCluster bootstrap() {
        return new OpenShift();
    }

    default Config getConfig(String kubeconfigPath) throws IOException {
        String content = Files.readString(Paths.get(kubeconfigPath));
        return Config.fromKubeconfig(content);
    }

}
