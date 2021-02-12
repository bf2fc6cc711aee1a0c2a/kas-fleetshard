package org.bf2.sync;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.sync.controlplane.ControlPlane;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ReadinessHealthCheck implements HealthCheck {

    @Inject
    ControlPlane controlPlane;

    @Override
    public HealthCheckResponse call() {
        try {
            this.controlPlane.getKafkaClusters();
            return HealthCheckResponse.up("Ready");
        } catch (RuntimeException e) {
            return HealthCheckResponse.down("Failed to reach control plane");
        }
    }
}