package org.bf2.operator;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ReadinessHealthCheck implements HealthCheck {

    @Inject
    InformerManager informerManager;

    @Override
    public HealthCheckResponse call() {
        if (informerManager.isReady()) {
            return HealthCheckResponse.up("Ready");
        }
        return HealthCheckResponse.down("Not Ready");
    }
}