package org.bf2.sync.health;

import org.bf2.common.ResourceInformerFactory;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@Liveness
@ApplicationScoped
public class LivenessHealthCheck implements HealthCheck {

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    @Override
    public HealthCheckResponse call() {
        if (this.resourceInformerFactory.allInformersWatching()) {
            return HealthCheckResponse.up("Informers are watching");
        }
        return HealthCheckResponse.down("Informers are not watching");
    }
}
