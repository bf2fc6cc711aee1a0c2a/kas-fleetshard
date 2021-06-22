package org.bf2.performance.k8s;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import org.bf2.test.k8s.KubeClient;
import org.bf2.test.k8s.cmdClient.KubeCmdClient;
import org.bf2.test.k8s.cmdClient.Oc;

import java.io.IOException;

public class OpenShift implements KubeCluster {

    private static final String OC = "oc";

    @Override
    public KubeCmdClient<Oc> defaultCmdClient(String kubeconfig, KubeClient client) {
        return new Oc(kubeconfig);
    }

    @Override
    public KubeClient defaultClient(String kubeconfig) throws IOException {
        Config config = getConfig(kubeconfig);
        config.setConnectionTimeout(30_000);
        // TODO: enable with fabric8 5.5+
        //config.setRequestRetryBackoffInterval(1000);
        //config.setRequestRetryBackoffLimit(5);
        return new KubeClient(new DefaultOpenShiftClient(new OpenShiftConfig(config)));
    }

    @Override
    public String toString() {
        return OC;
    }
}
