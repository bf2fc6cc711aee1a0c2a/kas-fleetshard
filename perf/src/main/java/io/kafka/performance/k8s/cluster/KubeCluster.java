package io.kafka.performance.k8s.cluster;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.kafka.performance.k8s.KubeClient;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public interface KubeCluster {

    /**
     * Return a default CMD cmdClient for this kind of cluster.
     * @param client
     */
    KubeCmdClient defaultCmdClient(String kubeconfig, KubeClient client);

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

    default Config getConfig(String apiUrl, String username, String token) {
        return new ConfigBuilder()
                .withMasterUrl(apiUrl)
                .withUsername(username)
                .withOauthToken(token)
                .build();
    }
}
