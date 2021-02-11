package org.bf2.sync;
import java.io.IOException;

import javax.enterprise.context.ApplicationScoped;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ReadinessHealthCheck implements HealthCheck {

    @ConfigProperty(name = "sync.kas-fleetshard-operator.url", defaultValue = "http://kas-fleetshard-operator:8080")
    String fleetShardURL;

    private CloseableHttpClient httpClient = HttpClients.createDefault();

    @Override
    public HealthCheckResponse call() {
        if (isOperatorReady()) {
            return HealthCheckResponse.up("Ready");
        }
        return HealthCheckResponse.down("Fleet shard operator is not available and it's liveness check failed");
    }

    public boolean isOperatorReady() {
        HttpGet request = new HttpGet(fleetShardURL+"/q/health/live");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            if (response.getStatusLine().getStatusCode() == 200) {
                return true;
            }
        } catch (IOException e) {
            // noop
        }
        return false;
    }
}