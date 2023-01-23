package org.bf2.systemtest.api.sync;

import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatus;
import org.bf2.systemtest.framework.ThrowableSupplier;
import org.bf2.test.TestUtils;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SyncApiClient {
    public static final String BASE_PATH = "/api/kafkas_mgmt/v1/agent-clusters/";

    private static final Logger LOGGER = LogManager.getLogger(SyncApiClient.class);
    private static final int MAX_RESEND = 10;

    public static HttpResponse<String> createManagedKafka(ManagedKafka managedKafka, String endpoint) throws Exception {
        LOGGER.info("Create managed kafka {}", managedKafka.getMetadata().getName());
        URI uri = URI.create(endpoint + BASE_PATH + "pepa/kafkas/");
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
        URI uri = URI.create(endpoint + BASE_PATH + "pepa/kafkas/" + id);
        LOGGER.info("Sending DELETE request to {} with port {} and path {}", uri.getHost(), uri.getPort(), uri.getPath());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .DELETE()
                .timeout(Duration.ofMinutes(2))
                .build();
        return retry(() -> client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public static HttpResponse<String> deleteManagedKafkas(String endpoint) throws Exception {
        LOGGER.info("Deleting all managed kafkas");
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create(endpoint + BASE_PATH + "pepa/kafkas");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .DELETE()
                .timeout(Duration.ofMinutes(2))
                .build();
        LOGGER.info("Sending DELETE request to {} with port {} and path {}", uri.getHost(), uri.getPort(), uri.getPath());
        return retry(() -> client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public static HttpResponse<String> getManagedKafkaAgentStatus(String endpoint) throws Exception {
        LOGGER.info("Get managed kafka agent status");
        return getRequest("pepa/status", endpoint);
    }

    public static HttpResponse<String> getManagedKafkaStatus(String id, String endpoint) throws Exception {
        LOGGER.info("Get managed kafka status of {}", id);
        return getRequest("pepa/kafkas/" + id + "/status", endpoint);
    }

    private static HttpResponse<String> getRequest(String path, String endpoint) throws Exception {
        URI uri = URI.create(endpoint + BASE_PATH + path);
        LOGGER.info("Sending GET request to {} with port {} and path {}", uri.getHost(), uri.getPort(), uri.getPath());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .timeout(Duration.ofMinutes(2))
                .build();
        return retry(() -> client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public static String getLatestStrimziVersion(String endpoint) throws Exception {
        return getSortedAvailableStrimziVersions(endpoint).reduce((first, second) -> second).orElse(null);
    }

    public static String getLatestKafkaVersion(String endpoint, String strimziVersion) throws Exception {
        return getLatestAvailableKafkaVersion(() -> {
            try {
                return Serialization.jsonMapper()
                        .readValue(SyncApiClient.getManagedKafkaAgentStatus(endpoint).body(),
                                ManagedKafkaAgentStatus.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, strimziVersion);
    }

    public static String getPreviousStrimziVersion(String endpoint) throws Exception {
        List<String> strimziVersions = getSortedAvailableStrimziVersions(endpoint).collect(Collectors.toList());
        return strimziVersions.get(strimziVersions.size() - 2);
    }

    private static Stream<String> getSortedAvailableStrimziVersions(String endpoint) throws Exception {
        return getSortedAvailableStrimziVersions(() -> {
            try {
                return Serialization.jsonMapper()
                        .readValue(SyncApiClient.getManagedKafkaAgentStatus(endpoint).body(),
                                ManagedKafkaAgentStatus.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public static Stream<String> getSortedAvailableStrimziVersions(Supplier<ManagedKafkaAgentStatus> statusSupplier) {
        AtomicReference<ManagedKafkaAgentStatus> agentStatus = new AtomicReference<>();

        TestUtils.waitFor("Strimzi version is reported", 1_000, 60_000, () -> {
            try {
                ManagedKafkaAgentStatus status = statusSupplier.get();
                agentStatus.set(status);
                return !status.getStrimzi().isEmpty();
            } catch (Exception e) {
                return false;
            }
        });

        return sortedStrimziVersion(agentStatus.get().getStrimzi().stream().map(StrimziVersionStatus::getVersion));
    }

    public static Stream<String> getKafkaVersions(Supplier<ManagedKafkaAgentStatus> statusSupplier, String strimziVersion) {
        AtomicReference<ManagedKafkaAgentStatus> agentStatus = new AtomicReference<>();

        TestUtils.waitFor("Strimzi version is reported", 1_000, 60_000, () -> {
            try {
                ManagedKafkaAgentStatus status = statusSupplier.get();
                agentStatus.set(status);
                return !status.getStrimzi().isEmpty();
            } catch (Exception e) {
                return false;
            }
        });

        return agentStatus.get()
                .getStrimzi()
                .stream()
                .filter(item -> item.getVersion().equals(strimziVersion))
                .findFirst()
                .map(selectedVersion -> selectedVersion.getKafkaVersions().stream())
                .orElseGet(Stream::empty);
    }

    public static String getLatestAvailableKafkaVersion(Supplier<ManagedKafkaAgentStatus> statusSupplier, String strimziVersion) {
        return getKafkaVersions(statusSupplier, strimziVersion).sorted((a, b) -> {
            ComparableVersion aVersion = new ComparableVersion(a);
            ComparableVersion bVersion = new ComparableVersion(b);
            return aVersion.compareTo(bVersion);
        }).reduce((first, second) -> second).get();
    }

    public static Stream<String> sortedStrimziVersion(Stream<String> versions) {
        Pattern pattern = Pattern.compile("^.*\\.v(?<version>[0-9]+\\.[0-9]+\\.[0-9]+[-0-9]*)$");
        return Objects.requireNonNull(versions.sorted((a, b) -> {
            Matcher aMatcher = pattern.matcher(a);
            Matcher bMatcher = pattern.matcher(b);
            aMatcher.matches();
            bMatcher.matches();
            ComparableVersion aVersion = new ComparableVersion(aMatcher.group("version"));
            ComparableVersion bVersion = new ComparableVersion(bMatcher.group("version"));
            return aVersion.compareTo(bVersion);
        }));
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
