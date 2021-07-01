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
import org.bf2.test.TestUtils;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides tools for waiting for managedkafka CR up
 */
public class ManagedKafkaDeployment {
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaDeployment.class);
    private final KubeClusterResource cluster;
    private final Kafka kafka;
    private CompletableFuture<Void> readyFuture;
    private final ManagedKafka managedKafka;

    public ManagedKafkaDeployment(ManagedKafka managedKafka, Kafka kafka, KubeClusterResource cluster) {
        this.managedKafka = managedKafka;
        this.kafka = kafka;
        this.cluster = cluster;
    }

    public void start() {
        readyFuture = TestUtils.asyncWaitFor("cluster ready", 1000, 600_000, this::isReady);
    }

    public String getBootstrapHostUrl() {
        return String.format("%s:%s", managedKafka.getSpec().getEndpoint().getBootstrapServerHost(), 443);
    }

    /**
     * Wait until kafka CR is up and running
     *
     * @return boostrap url
     * @throws Exception
     */
    public String waitUntilReady() throws Exception {
        try {
            readyFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        return getBootstrapHostUrl();
    }

    /**
     * Returns a future that will go ready when the Kafka deployment becomes ready
     *
     * @return future
     */
    public Future<Void> readyFuture() {
        return readyFuture;
    }

    private AtomicInteger count = new AtomicInteger();

    private boolean isReady() {
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
                        return true;
                    } else if (ManagedKafkaCondition.Reason.Error.name().equals(ready.get().getReason())) {
                        throw new IllegalStateException("Error creating ManagedKafka");
                    }
                }
            }
        }

        if (count.getAndIncrement() % 15 == 0) {
            ListOptions opts = new ListOptionsBuilder().withFieldSelector("status.phase=Pending").build();
            cluster.kubeClient().client().pods().inNamespace(kafka.getMetadata().getNamespace())
                    .withLabel("app.kubernetes.io/instance", kafka.getMetadata().getName()).list(opts).getItems().forEach(ManagedKafkaDeployment::checkUnschedulablePod);
        }
        return false;
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
