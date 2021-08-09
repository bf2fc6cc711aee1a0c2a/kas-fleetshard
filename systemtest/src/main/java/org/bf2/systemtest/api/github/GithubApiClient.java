package org.bf2.systemtest.api.github;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public class GithubApiClient {

    public static Release[] getReleases(String project, String repo) throws ExecutionException, InterruptedException {
        UncheckedObjectMapper uncheckedObjectMapper = new UncheckedObjectMapper();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + project + "/" + repo + "/releases"))
                .GET()
                .timeout(Duration.ofMinutes(2))
                .build();

        return client
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(uncheckedObjectMapper::readValue)
                .get();
    }

    static class UncheckedObjectMapper extends com.fasterxml.jackson.databind.ObjectMapper {
        Release[] readValue(String content) {
            try {
                return this.readValue(content, new TypeReference<>() {
                });
            } catch (IOException ioe) {
                throw new CompletionException(ioe);
            }
        }

    }
}
