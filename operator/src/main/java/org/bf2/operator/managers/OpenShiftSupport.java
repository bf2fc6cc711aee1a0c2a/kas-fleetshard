package org.bf2.operator.managers;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.client.OpenShiftClient;

import javax.enterprise.context.ApplicationScoped;

/**
 * Allows for tests act as if openshift is supported
 * - may be addressed by fabric8 changes in 5.10, the
 *   client created for openshift mock support should be adaptable
 *   however I have not checked if quarkus is injecting that client
 */
@ApplicationScoped
public class OpenShiftSupport {

    public boolean isOpenShift(KubernetesClient client) {
        return client.isAdaptable(OpenShiftClient.class);
    }

    public OpenShiftClient adapt(KubernetesClient client) {
        return client.adapt(OpenShiftClient.class);
    }

}
