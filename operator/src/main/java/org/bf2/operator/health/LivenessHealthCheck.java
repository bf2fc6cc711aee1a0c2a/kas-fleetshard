package org.bf2.operator.health;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.InformerManager;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@ApplicationScoped
public class LivenessHealthCheck implements HealthCheck {

    @Inject
    InformerManager informers;

    @Override
    public HealthCheckResponse call() {
        if (this.informers != null && this.informers.isReady()) {
            return HealthCheckResponse.up("Informers are Ready");
        }
        return HealthCheckResponse.down("Informers are not ready yet");
    }
}
