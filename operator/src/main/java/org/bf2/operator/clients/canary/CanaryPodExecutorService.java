package org.bf2.operator.clients.canary;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.arc.properties.IfBuildProperty;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Obtain the canary's Status response by executing curl directly in the pod. This
 * method is meant for use in local development mode where the kubectl session is
 * kubeadmin and calling the canary via the service is not possible.
 */
@ApplicationScoped
@IfBuildProperty(name = "canary-status", stringValue = "pod-exec")
public class CanaryPodExecutorService extends CanaryStatusService {
    @Inject
    KubernetesClient kubernetesClient;

    @Override
    public Status get(ManagedKafka managedKafka) throws Exception {
        return kubernetesClient
                .pods()
                .inNamespace(managedKafka.getMetadata().getNamespace())
                .withLabel("app.kubernetes.io/component", "canary")
                .list()
                .getItems()
                .stream()
                .findFirst()
                .map(canaryPod -> {
                    CompletableFuture<String> promise = new CompletableFuture<>();
                    ByteArrayOutputStream data = new ByteArrayOutputStream();

                    kubernetesClient
                        .pods()
                        .inNamespace(managedKafka.getMetadata().getNamespace())
                        .withName(canaryPod.getMetadata().getName())
                        .writingOutput(data)
                        .writingError(data)
                        .usingListener(new ExecListener() {
                            @Override
                            public void onClose(int code, String reason) {
                                promise.complete(data.toString(StandardCharsets.UTF_8));
                            }

                            @Override
                            public void onFailure(Throwable t, Response failureResponse) {
                                promise.completeExceptionally(t);
                            }
                        })
                        .exec("curl", "-s", "localhost:8080/status");

                    return promise;
                })
                .orElse(CompletableFuture.completedFuture(null))
                .thenApply(response -> Serialization.unmarshal(response, Status.class))
                .get(30, TimeUnit.SECONDS);        }
}