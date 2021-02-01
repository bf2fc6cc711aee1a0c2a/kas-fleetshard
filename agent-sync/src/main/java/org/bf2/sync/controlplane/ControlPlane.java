package org.bf2.sync.controlplane;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class ControlPlane {

    @ConfigProperty(name = "cluster.id")
    String id;

    @Inject
    @RestClient
    ControlPlaneRestClient controlPlaneClient;

    /* holds a copy of the remote state
     * TODO: validate this can be in-memory
     * an assumption is that we'll also see the deleted flag set
     * to remove entries
     */
    private ConcurrentHashMap<String, ManagedKafka> managedKafkas = new ConcurrentHashMap<>();

    public void addManagedKafka(ManagedKafka remoteManagedKafka) {
        managedKafkas.put(remoteManagedKafka.getKafkaClusterId(), remoteManagedKafka);
    }

    public void removeManagedKafka(ManagedKafka remoteManagedKafka) {
        managedKafkas.remove(remoteManagedKafka.getKafkaClusterId());
    }

    public ManagedKafka getManagedKafka(ManagedKafka localManagedKafka) {
        return managedKafkas.get(localManagedKafka.getKafkaClusterId());
    }

    public CompletableFuture<Void> updateStatus(ManagedKafkaAgentStatus status) {
        return controlPlaneClient.updateStatus(id, status);
    }

    /**
     * Get the current list of ManagedKafka clusters from the control plane
     * Also updates the cache of ManagedKafka instances
     * @return
     */
    public List<ManagedKafka> getKafkaClusters() {
        return controlPlaneClient.getKafkaClusters(id);
    }

    public CompletableFuture<Void> updateKafkaClusterStatus(ManagedKafkaStatus status, String clusterId) {
        return controlPlaneClient.updateKafkaClustersStatus(id, Map.of(clusterId, status));
    }

    /**
     * Update the control plane with the status of this ManagedKafka, but
     * only if it's different than what the control plane already knows.
     *
     * TODO: on a restart we'll hit add/update again for each resource
     * - that will be filtered only if a remote poll has been completed
     *
     * @param managedKafka
     */
    public void updateKafkaClusterStatus(ManagedKafka managedKafka) {
        ManagedKafka remote = getManagedKafka(managedKafka);
        if (remote == null || isStatusDifferent(remote, managedKafka)) {
            //fire and forget
            updateKafkaClusterStatus(managedKafka.getStatus(), id);
            //on the next poll the remote should be updated, if not we'll update again at the resync
        }
    }

    public boolean isStatusDifferent(ManagedKafka remote, ManagedKafka local) {
        //TODO: implement me
        return true;
    }

}
