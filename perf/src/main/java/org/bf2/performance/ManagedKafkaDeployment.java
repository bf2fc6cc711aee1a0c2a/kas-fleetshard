package org.bf2.performance;

import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.test.TestUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * Provides tools for waiting for managedkafka CR up
 */
public class ManagedKafkaDeployment {
    private final KubeClusterResource cluster;
    private CompletableFuture<Void> readyFuture;
    private final ManagedKafka managedKafka;

    public ManagedKafkaDeployment(ManagedKafka managedKafka, KubeClusterResource cluster) {
        this.managedKafka = managedKafka;
        this.cluster = cluster;
    }

    public void start() {
        ManagedKafkaResourceType type = new ManagedKafkaResourceType();
        Resource<ManagedKafka> resource = type.resource(cluster.kubeClient(), managedKafka);
        Predicate<ManagedKafka> readyCheck = type.readiness(cluster.kubeClient());
        readyFuture = TestUtils.asyncWaitFor("cluster ready", 1000, 600_000, () -> readyCheck.test(resource.get()));
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

    public ManagedKafka getManagedKafka() {
        return managedKafka;
    }

}
