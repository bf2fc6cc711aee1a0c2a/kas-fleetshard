package org.bf2.performance;

import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.status.KafkaStatus;
import io.strimzi.api.kafka.model.status.ListenerStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.performance.k8s.KubeClusterResource;

import java.io.IOException;
import java.io.UncheckedIOException;
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
    private final ClusterKafkaProvisioner provisioner;
    private final KubeClusterResource cluster;
    private final Kafka kafka;
    private final CompletableFuture<String> readyFuture = new CompletableFuture<>();
    private final ManagedKafka managedKafka;

    public KafkaDeployment(ManagedKafka managedKafka, Kafka kafka, ClusterKafkaProvisioner clusterKafkaProvisioner) {
        this.managedKafka = managedKafka;
        this.kafka = kafka;
        this.provisioner = clusterKafkaProvisioner;
        this.cluster = clusterKafkaProvisioner.getKubernetesCluster();
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
        try {
            var client = cluster.kubeClient().client().customResources(Kafka.class);
            int count = 0;
            while (!readyFuture.isCancelled()) {
                ManagedKafka currentManagedKafka = cluster.kubeClient()
                        .client()
                        .customResources(ManagedKafka.class)
                        .inNamespace(managedKafka.getMetadata().getNamespace())
                        .withName(managedKafka.getMetadata().getName())
                        .get();
                boolean mkReady = false;
                if (currentManagedKafka != null) {
                    ManagedKafkaStatus status = currentManagedKafka.getStatus();
                    if (status != null && status.getConditions() != null) {
                        Optional<ManagedKafkaCondition> ready = status.getConditions().stream().filter(c -> ManagedKafkaCondition.Type.Ready.name().equals(c.getType())).findFirst();
                        if (ready.isPresent()) {
                            if (Boolean.valueOf(ready.get().getStatus())) {
                                mkReady = true;
                            } else if (ManagedKafkaCondition.Reason.Error.name().equals(ready.get().getReason())) {
                                throw new IllegalStateException("Error creating ManagedKafka");
                            }
                        }
                    }
                }

                Kafka current = client.inNamespace(kafka.getMetadata().getNamespace()).withName(kafka.getMetadata().getName()).get();
                if (mkReady && current != null && current.getStatus() != null && current.getStatus().getListeners() != null && current.getStatus().getConditions() != null) {
                    KafkaStatus status = current.getStatus();
                    Optional<Boolean> ready = status.getConditions().stream().filter(c -> "Ready".equals(c.getType())).map(c -> Boolean.parseBoolean(c.getStatus())).findFirst();
                    Optional<String> bootstrap = status.getListeners().stream().filter(l -> l.getBootstrapServers() != null).map(ListenerStatus::getBootstrapServers).findFirst();
                    if (ready.isPresent() && ready.get() && bootstrap.isPresent()) {
                        LOGGER.info("Cluster {} deployed", managedKafka.getMetadata().getName());
                        TestMetadataCapture.getInstance().storeKafkaCluster(cluster, kafka);
                        provisioner.getMonitoring().connectNamespaceToMonitoringStack(cluster.kubeClient(), managedKafka.getMetadata().getNamespace());
                        return bootstrap.get();
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
