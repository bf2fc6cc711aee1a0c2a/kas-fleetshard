package org.bf2.operator;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import io.fabric8.kubernetes.client.KubernetesClient;

@Liveness
@ApplicationScoped
public class LivenessHealthCheck implements HealthCheck {

    @Inject
    protected KubernetesClient kubernetesClient;

    @Override
    public HealthCheckResponse call() {
        if (this.kubernetesClient.namespaces().list().getItems().size() > 1) {
            return HealthCheckResponse.up("alive");
        }
        return HealthCheckResponse.down("can't reach kube api, liveness check failed");
    }
}
