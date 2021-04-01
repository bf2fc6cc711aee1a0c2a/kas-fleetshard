package org.bf2.sync.controlplane;

import static java.util.Objects.requireNonNullElse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.micrometer.core.annotation.Counted;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ControlPlane {

    private static final ManagedKafkaStatus EMPTY_MANAGED_KAFKA_STATUS = new ManagedKafkaStatus();

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
        managedKafkas.put(managedKafkaKey(remoteManagedKafka), remoteManagedKafka);
    }

    public void removeManagedKafka(ManagedKafka remoteManagedKafka) {
        managedKafkas.remove(managedKafkaKey(remoteManagedKafka));
    }

    public ManagedKafka getManagedKafka(String id) {
        return managedKafkas.get(id);
    }

    public static String managedKafkaKey(ManagedKafka kafka) {
        return kafka.getId() + "/" + kafka.getPlacementId();
    }

    /**
     * Make an async call to update the status, but only if it is different that the old.
     *
     * newAgent is expected to be non-null
     */
    public void updateAgentStatus(ManagedKafkaAgent oldAgent, ManagedKafkaAgent newAgent) {
        if (oldAgent != null) {
            // this is an update event in which the resourceVersion has changed
            // it may be due to a spec change, but those happen infrequently
            updateAgentStatus();
        }
    }

    private void updateAgentStatus() {
        log.debug("Updating agnet status");
        executorService.execute(() -> {
            ManagedKafkaAgent localManagedKafkaAgent = localLookup.getLocalManagedKafkaAgent();
            if (localManagedKafkaAgent != null) {
                controlPlaneClient.updateStatus(id, localManagedKafkaAgent.getStatus());
            }
            // TODO if it's null we could still send an empty status
        });
    }

    /**
     * Get the current list of ManagedKafka clusters from the control plane
     * as a blocking call.
     * Also updates the cache of ManagedKafka instances
     */
    public List<ManagedKafka> getKafkaClusters() {
        ManagedKafkaList result = controlPlaneClient.getKafkaClusters(id);
        result.getItems().forEach((mk)->addManagedKafka(mk));
        return result.getItems();
    }

    /**
     * Get the ManagedKafkaAgent as a blocking call.
     */
    public ManagedKafkaAgent getManagedKafkaAgent() {
        return controlPlaneClient.get(id);
    }

    /**
     * Make an async call to update the status.
     */
    public void updateKafkaClusterStatus(String localMetaNamespaceKey, String clusterId) {
        updateKafkaClusterStatus(() -> {
            ManagedKafka kafka = localLookup.getLocalManagedKafka(localMetaNamespaceKey);
            if (kafka == null) {
                return Collections.emptyMap();
            }
            // for consistency we'll send an empty status
            return Map.<String, ManagedKafkaStatus>of(clusterId, requireNonNullElse(kafka.getStatus(), EMPTY_MANAGED_KAFKA_STATUS));
        });
    }

    /**
     * Make an async call to update the status.
     *
     * A {@link Supplier} is used to defer the map construction.
     */
    public void updateKafkaClusterStatus(Supplier<Map<String, ManagedKafkaStatus>> statusSupplier) {
        log.debug("Updating managedkafka(s) status");
        executorService.execute(() -> {
            Map<String, ManagedKafkaStatus> status = statusSupplier.get();
            if (status.isEmpty()) {
                return;
            }
            controlPlaneClient.updateKafkaClustersStatus(id, status);
        });
    }

    /**
     * Async update the control plane with the status of this ManagedKafka, but
     * only if it's different than the old
     *
     * newManagedKafka is expected to be non-null as deletes are not processed
     */
    public void updateKafkaClusterStatus(ManagedKafka oldManagedKafka, ManagedKafka newManagedKafka) {
        if (newManagedKafka.getId() != null && oldManagedKafka != null && statusChanged(oldManagedKafka.getStatus(), newManagedKafka.getStatus())) {
            // send a status update immediately (async)
            updateKafkaClusterStatus(Cache.metaNamespaceKeyFunc(newManagedKafka), newManagedKafka.getId());
        }
    }

    static boolean statusChanged(ManagedKafkaStatus oldStatus, ManagedKafkaStatus newStatus) {
        if (oldStatus == null) {
            return newStatus != null;
        }
        if (newStatus == null) {
            return false;
        }
        return !Objects.equals(oldStatus.getUpdatedTimestamp(), newStatus.getUpdatedTimestamp());
    }

    /**
     * On the resync interval, send everything
     */
    @Counted(value = "status_resync") // no need to be timed as the actions are async
    @Scheduled(every = "{resync.interval}", delayed = "10s")
    public void sendResync() {
        log.debug("Updating status on resync interval");
        updateKafkaClusterStatus(() -> {
            return localLookup.getLocalManagedKafkas().stream().filter(mk -> mk.getId() != null)
                    .collect(Collectors.toMap(ManagedKafka::getId,
                            (mk) -> requireNonNullElse(mk.getStatus(), EMPTY_MANAGED_KAFKA_STATUS)));
        });
        updateAgentStatus();
    }

}
