package io.kafka.performance.k8s.cluster;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.kafka.performance.k8s.KubeClient;
import io.kafka.performance.k8s.cmd.KubeCmdClient;
import io.kafka.performance.k8s.cmd.Oc;

import java.io.IOException;

public class OpenShift implements KubeCluster {

    private static final String OC = "oc";

    @Override
    public KubeCmdClient defaultCmdClient(String kubeconfig, KubeClient client) {
        return new Oc(client.getNamespace(), kubeconfig);
    }

    @Override
    public KubeClient defaultClient(String kubeconfig) throws IOException {
        Config config = getConfig(kubeconfig);
        config.setConnectionTimeout(30_000);
        config.setRequestRetryBackoffInterval(1000);
        config.setRequestRetryBackoffLimit(5);
        return new KubeClient(new DefaultOpenShiftClient(new OpenShiftConfig(config)), "default");
    }

    @Override
    public String toString() {
        return OC;
    }
}
