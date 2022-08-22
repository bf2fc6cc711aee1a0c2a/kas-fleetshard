package org.bf2.operator.managers;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.quarkus.test.Mock;

import javax.enterprise.context.ApplicationScoped;

@Mock
@ApplicationScoped
public class MockOpenShiftSupport extends OpenShiftSupport {

    boolean openshift = true;
    OpenShiftClient client = null;

    @Override
    public boolean isOpenShift(KubernetesClient client) {
        return openshift;
    }

    public void setOpenShift(boolean value) {
        this.openshift = value;
    }

    @Override
    public OpenShiftClient adapt(KubernetesClient client) {
        return this.client != null ?
                this.client :
                    new DefaultOpenShiftClient(client.getHttpClient(),
                            OpenShiftConfig.wrap(client.getConfiguration()));
    }

    public void setClient(OpenShiftClient client) {
        this.client = client;
    }
}
