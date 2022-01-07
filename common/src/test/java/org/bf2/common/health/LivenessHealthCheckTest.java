package org.bf2.common.health;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LivenessHealthCheckTest {

    @Test public void testLiveness() {
        // check the dummy behavior - will need to be updated when there is something real
        LivenessHealthCheck livenessHealthCheck = new LivenessHealthCheck();
        assertEquals(HealthCheckResponse.Status.UP, livenessHealthCheck.call().getStatus());
    }

}
