package org.bf2.operator.health;

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

        // Temporary disabling health check based on Informers watching due to a fabric8 bug fixed in 5.8.0 version
        // https://github.com/fabric8io/kubernetes-client/pull/3485
        /*
        if (this.resourceInformerFactory.allInformersWatching()) {
            return HealthCheckResponse.up("Informers are watching");
        }
        return HealthCheckResponse.down("Informers are not watching");
        */
        return HealthCheckResponse.up("Operator up and running");
    }
}
