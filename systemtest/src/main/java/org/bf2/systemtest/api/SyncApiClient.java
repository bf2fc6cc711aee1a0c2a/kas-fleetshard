package org.bf2.systemtest.api;

import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SyncApiClient {
    private static final Logger LOGGER = LogManager.getLogger(SyncApiClient.class);

    public static HttpResponse<String> createManagedKafka(ManagedKafka managedKafka, String endpoint) throws IOException, InterruptedException {
        LOGGER.info("Create managed kafka {}", managedKafka.getMetadata().getName());
        URI uri = URI.create(endpoint + "/api/managed-services-api/v1/agent-clusters/pepa/kafkas/");
        LOGGER.info("Sending POST request to {} with port {} and path {}", uri.getHost(), uri.getPort(), uri.getPath());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Serialization.asJson(managedKafka)))
                .timeout(Duration.ofMinutes(2))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> deleteManagedKafka(String name, String endpoint) throws IOException, InterruptedException {
        LOGGER.info("Delete managed kafka {}", name);
        URI uri = URI.create(endpoint + "/api/managed-services-api/v1/agent-clusters/pepa/kafkas/" + name);
        LOGGER.info("Sending DELETE request to {} with port {} and path {}", uri.getHost(), uri.getPort(), uri.getPath());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .DELETE()
                .timeout(Duration.ofMinutes(2))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
