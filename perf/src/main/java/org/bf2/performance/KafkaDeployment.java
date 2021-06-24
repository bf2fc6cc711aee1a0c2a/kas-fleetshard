package org.bf2.performance;

import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.performance.framework.KubeClusterResource;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provides tools for waiting for kafka CR up
 */
public class KafkaDeployment {
    private static final Logger LOGGER = LogManager.getLogger(KafkaDeployment.class);
    private final KubeClusterResource cluster;
    private final Kafka kafka;
    private final CompletableFuture<String> readyFuture = new CompletableFuture<>();
    private final ManagedKafka managedKafka;

    public KafkaDeployment(ManagedKafka managedKafka, Kafka kafka, KubeClusterResource cluster) {
        this.managedKafka = managedKafka;
        this.kafka = kafka;
        this.cluster = cluster;
    }

    public void start() {
        new Thread(() -> {
            try {
                readyFuture.complete(waitUntilReadyOrCancelled());
            } catch (Throwable t) {
                readyFuture.completeExceptionally(t);
            }
        }).start();
    }


    /**
     * Wait until kafka CR is up and running
     *
     * @return boostrap url
     * @throws Exception
     */
    public String waitUntilReady() throws Exception {
        try {
            return readyFuture.get(600_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            } else {
                throw new RuntimeException(e);
            }
        } catch (TimeoutException e) {
            throw new RuntimeException(String.format("Unable to find listener hostname for cluster %s within timeout", kafka.getMetadata().getName()));
        }
    }

    /**
     * Returns a future that will go ready when the Kafka deployment becomes ready
     *
     * @return future
     */
    public Future<String> readyFuture() {
        return readyFuture;
    }

    private String waitUntilReadyOrCancelled() {
        int count = 0;
        while (!readyFuture.isCancelled()) {
            ManagedKafka currentManagedKafka = cluster.kubeClient()
                    .client()
                    .customResources(ManagedKafka.class)
                    .inNamespace(managedKafka.getMetadata().getNamespace())
                    .withName(managedKafka.getMetadata().getName())
                    .get();
            if (currentManagedKafka != null) {
                ManagedKafkaStatus status = currentManagedKafka.getStatus();
                if (status != null && status.getConditions() != null) {
                    Optional<ManagedKafkaCondition> ready = status.getConditions().stream().filter(c -> ManagedKafkaCondition.Type.Ready.name().equals(c.getType())).findFirst();
                    if (ready.isPresent()) {
                        if (Boolean.valueOf(ready.get().getStatus())) {
                            return currentManagedKafka.getSpec().getEndpoint().getBootstrapServerHost();
                        } else if (ManagedKafkaCondition.Reason.Error.name().equals(ready.get().getReason())) {
                            throw new IllegalStateException("Error creating ManagedKafka");
                        }
                    }
                }
            }

            if (count++ % 15 == 0) {
                ListOptions opts = new ListOptionsBuilder().withFieldSelector("status.phase=Pending").build();
                cluster.kubeClient().client().pods().inNamespace(kafka.getMetadata().getNamespace())
                        .withLabel("app.kubernetes.io/instance", kafka.getMetadata().getName()).list(opts).getItems().forEach(KafkaDeployment::checkUnschedulablePod);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    public Kafka getKafka() {
        return kafka;
    }

    private static void checkUnschedulablePod(Pod p) {
        p.getStatus().getConditions().stream().filter(c -> "Unschedulable".equals(c.getReason())).forEach(c -> {
            LOGGER.info("Pod {} unschedulable {}", p.getMetadata().getName(), c.getMessage());
            throw new UnschedulablePodException(String.format("Unschedulable pod %s : %s", p.getMetadata().getName(), c.getMessage()));
        });
    }

    public ManagedKafka getManagedKafka() {
        return managedKafka;
    }

}
