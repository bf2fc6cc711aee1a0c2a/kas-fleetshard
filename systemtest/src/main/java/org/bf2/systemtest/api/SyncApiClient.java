package org.bf2.systemtest.api;

import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.systemtest.framework.ThrowableSupplier;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SyncApiClient {
    private static final Logger LOGGER = LogManager.getLogger(SyncApiClient.class);
    private static final int MAX_RESEND = 10;

    public static HttpResponse<String> createManagedKafka(ManagedKafka managedKafka, String endpoint) throws Exception {
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
        return retry(() -> client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public static HttpResponse<String> deleteManagedKafka(String id, String endpoint) throws Exception {
        LOGGER.info("Delete managed kafka {}", id);
        URI uri = URI.create(endpoint + "/api/managed-services-api/v1/agent-clusters/pepa/kafkas/" + id);
        LOGGER.info("Sending DELETE request to {} with port {} and path {}", uri.getHost(), uri.getPort(), uri.getPath());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .DELETE()
                .timeout(Duration.ofMinutes(2))
                .build();
        return retry(() -> client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public static HttpResponse<String> getManagedKafkaAgentStatus(String endpoint) throws Exception {
        LOGGER.info("Get managed kafka agent status");
        return getRequest("/api/managed-services-api/v1/agent-clusters/pepa/status", endpoint);
    }

    public static HttpResponse<String> getManagedKafkaStatus(String id, String endpoint) throws Exception {
        LOGGER.info("Get managed kafka status of {}", id);
        return getRequest("/api/managed-services-api/v1/agent-clusters/pepa/kafkas/" + id + "/status", endpoint);
    }

    private static HttpResponse<String> getRequest(String path, String endpoint) throws Exception {
        URI uri = URI.create(endpoint + path);
        LOGGER.info("Sending GET request to {} with port {} and path {}", uri.getHost(), uri.getPort(), uri.getPath());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .timeout(Duration.ofMinutes(2))
                .build();
        return retry(() -> client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    /**
     * Retry sync request in case connection refused
     *
     * @param apiRequest api request method
     */
    private static <T> HttpResponse<T> retry(ThrowableSupplier<HttpResponse<T>> apiRequest) throws Exception {
        for (int i = 1; i < MAX_RESEND; i++) {
            try {
                var res = apiRequest.get();
                if (res.statusCode() >= HttpURLConnection.HTTP_OK && res.statusCode() <= HttpURLConnection.HTTP_PARTIAL) {
                    return res;
                } else {
                    throw new Exception("Status code is " + res.statusCode());
                }
            } catch (Exception ex) {
                LOGGER.warn("Request failed {}, going to retry {}/{}", ex.getMessage(), i, MAX_RESEND);
                Thread.sleep(5_000);
            }
        }
        //last try
        return apiRequest.get();
    }
}
