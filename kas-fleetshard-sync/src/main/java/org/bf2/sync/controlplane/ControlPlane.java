package org.bf2.sync.controlplane;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.WebApplicationException;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.sync.ManagedKafkaSync;
import org.bf2.sync.informer.LocalLookup;
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

    @Inject
    ExecutorService executorService;

    @Inject
    LocalLookup localLookup;

    /* holds a copy of the remote state */
    private ConcurrentHashMap<String, ManagedKafka> managedKafkas = new ConcurrentHashMap<>();

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
        executorService.execute(() -> {
            try {
                controlPlaneClient.updateStatus(id, newAgent.getStatus());
            } catch (WebApplicationException e) {
                log.errorf(e, "Could not update status for ManagedKafkaAgent");
            }
        });
    }

    /**
     * Get the current list of ManagedKafka clusters from the control plane
     * as a blocking call.
     * Also updates the cache of ManagedKafka instances
     * @return
     */
    public List<ManagedKafka> getKafkaClusters() {
        List<ManagedKafka> result = controlPlaneClient.getKafkaClusters(id);
        result.forEach((mk)->addManagedKafka(mk));
        return result;
    }

    /**
     * Make an async call to update the status.  A default failure handler is already added.
     */
    public void updateKafkaClusterStatus(ManagedKafkaStatus status, String clusterId) {
        updateKafkaClusterStatus(()->{return Map.of(clusterId, status);});
    }

    /**
     * Make an async call to update the status.  A default failure handler is already added.
     *
     * A {@link Supplier} is used to defer the map construction.
     */
    public void updateKafkaClusterStatus(Supplier<Map<String, ManagedKafkaStatus>> statusSupplier) {
        executorService.execute(() -> {
            Map<String, ManagedKafkaStatus> status = statusSupplier.get();
            if (status.isEmpty()) {
                return;
            }
            try {
                controlPlaneClient.updateKafkaClustersStatus(id, status);
            } catch (WebApplicationException e) {
                log.errorf(e, "Could not update status for %s", status.keySet());
            }
        });
    }

    /**
     * Update the control plane with the status of this ManagedKafka, but
     * only if it's different than what the control plane already knows.
     *
     * newManagedKafka is expected to be non-null as deletes are not processed
     */
    public void updateKafkaClusterStatus(ManagedKafka oldManagedKafka, ManagedKafka newManagedKafka) {
        if (oldManagedKafka != null) {
            if (statusChanged(oldManagedKafka.getStatus(), newManagedKafka.getStatus())) {
                // send a status update immediately (async)
                updateKafkaClusterStatus(newManagedKafka.getStatus(), newManagedKafka.getId());
                return;
            }
            if (ManagedKafkaSync.specChanged(oldManagedKafka.getSpec(), newManagedKafka)) {
                // the control plane initiated this, so it doesn't need to be sent
                return;
            }
        }
    }

    boolean statusChanged(ManagedKafkaStatus oldStatus, ManagedKafkaStatus newStatus) {
        if (oldStatus == null) {
            return true;
        }
        // TODO: implement me
        return !oldStatus.equals(newStatus);
    }

    /**
     * There doesn't seem to be a great way to know when a resync is done,
     * so we'll just flush here
     */
    @Scheduled(every = "{resync.interval}", delayed = "{update.delayed}")
    public void sendResync() {
        updateKafkaClusterStatus(() -> {
            return localLookup.getLocalManagedKafkas().stream()
                    .collect(Collectors.toMap(ManagedKafka::getId, ManagedKafka::getStatus));
        });
    }

}
