package org.bf2.sync;

import org.bf2.sync.informer.InformerManager;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@Liveness
@ApplicationScoped
public class LivenessHealthCheck implements HealthCheck {

    @Inject
    InformerManager informers;

    @Override
    public HealthCheckResponse call() {
        if (this.informers != null && this.informers.isReady()
                && this.informers.isConfigurationSecretAvailable()
                && !this.informers.isConfigurationSecretChanged()) {
            return HealthCheckResponse.up("Informers are Ready");
        }
        return HealthCheckResponse.down("Informers are not ready yet");
    }
}