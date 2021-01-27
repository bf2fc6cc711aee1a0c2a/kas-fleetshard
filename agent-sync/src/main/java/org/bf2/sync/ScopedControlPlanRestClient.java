package org.bf2.sync;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Just a simple wrapper to encapsulate the cluster claim id
 * TODO: wire this up properly
 */
@ApplicationScoped
public class ScopedControlPlanRestClient {
    // TODO: where should this be coming from
    @ConfigProperty(name = "cluster.id")
    String id;

    @Inject
    @RestClient
    ControlPlaneRestClient controlPlane;

    public CompletableFuture<Void> updateStatus(ManagedKafkaAgentStatus status) {
        return controlPlane.updateStatus(status, id);
    }

    public List<ManagedKafka> getKafkaClusters() {
        return controlPlane.getKafkaClusters(id);
    }

    public CompletableFuture<Void> updateKafkaClusterStatus(ManagedKafkaStatus status, String clusterId) {
        return controlPlane.updateKafkaClusterStatus(status, id, clusterId);
    }
}
