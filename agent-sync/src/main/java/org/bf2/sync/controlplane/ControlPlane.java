package org.bf2.sync.controlplane;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ControlPlane {

    @Inject
    Logger log;

    @ConfigProperty(name = "cluster.id")
    String id;

    @Inject
    @RestClient
    ControlPlaneRestClient controlPlaneClient;

    /* holds a copy of the remote state */
    private ConcurrentHashMap<String, ManagedKafka> managedKafkas = new ConcurrentHashMap<>();

    private Map<String, ManagedKafkaStatus> pendingStatus = new HashMap<>();

    void addManagedKafka(ManagedKafka remoteManagedKafka) {
        managedKafkas.put(remoteManagedKafka.getId(), remoteManagedKafka);
    }

    public void removeManagedKafka(ManagedKafka remoteManagedKafka) {
        managedKafkas.remove(remoteManagedKafka.getId());
    }

    public ManagedKafka getManagedKafka(String id) {
        return managedKafkas.get(id);
    }

    /**
     * Make an async call to update the status.  A default failure handler is already added.
     */
    public void updateStatus(ManagedKafkaAgent oldAgent, ManagedKafkaAgent newAgent) {
        controlPlaneClient.updateStatus(id, newAgent.getStatus()).subscribe().with(
                item -> {},
                failure -> log.errorf(failure, "Could not update status for ManagedKafkaAgent"));
    }

    /**
     * Get the current list of ManagedKafka clusters from the control plane
     * Also updates the cache of ManagedKafka instances
     * @return
     */
    public List<ManagedKafka> getKafkaClusters() {
        return controlPlaneClient.getKafkaClusters(id)
                .onItem().invoke((mks)->{
                    mks.forEach((mk)->addManagedKafka(mk));
                })
                .await().indefinitely();
    }

    /**
     * Make an async call to update the status.  A default failure handler is already added.
     */
    public void updateKafkaClusterStatus(ManagedKafkaStatus status, String clusterId) {
        updateKafkaClusterStatus(Map.of(clusterId, status));
    }

    /**
     * Make an async call to update the status.  A default failure handler is already added.
     */
    public void updateKafkaClusterStatus(Map<String, ManagedKafkaStatus> status) {
        controlPlaneClient.updateKafkaClustersStatus(id, status).subscribe().with(
                item -> {},
                failure -> log.errorf(failure, "Could not update status for %s", status.keySet()));
    }

    /**
     * Update the control plane with the status of this ManagedKafka, but
     * only if it's different than what the control plane already knows.
     *
     * newManagedKafka is expected to be non-null as deletes are not processed
     */
    public void updateKafkaClusterStatus(ManagedKafka oldManagedKafka, ManagedKafka newManagedKafka) {
        if (oldManagedKafka != null && statusChanged(oldManagedKafka.getStatus(), newManagedKafka.getStatus())) {
            // send a status update immediately (async)
            updateKafkaClusterStatus(newManagedKafka.getStatus(), newManagedKafka.getId());
        } else {
            // TODO: if it's a spec change it can be filtered
            // send a resync in a batch
            synchronized (pendingStatus) {
                pendingStatus.put(newManagedKafka.getId(), newManagedKafka.getStatus());
            }
        }
    }

    boolean statusChanged(ManagedKafkaStatus oldStatus, ManagedKafkaStatus newStatus) {
        // TODO: implement me
        return true;
    }

    /**
     * There doesn't seem to be a great way to know when a resyn is done,
     * so we'll just flush here
     */
    @Scheduled(every = "{poll.interval}", delayed = "{update.delayed}")
    public void sendPendingStatusUpdates() {
        Map<String, ManagedKafkaStatus> toSend = null;
        synchronized (pendingStatus) {
            if (pendingStatus.isEmpty()) {
                return;
            }
            toSend = new HashMap<>(pendingStatus);
            pendingStatus.clear();
        }
        updateKafkaClusterStatus(toSend);
    }

}
