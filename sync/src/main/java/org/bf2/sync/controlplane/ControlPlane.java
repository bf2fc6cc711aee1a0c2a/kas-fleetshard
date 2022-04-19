package org.bf2.sync.controlplane;

import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.micrometer.core.annotation.Counted;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNullElse;

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

    /* holds a copy of the remote desired state */
    private ConcurrentHashMap<String, ManagedKafka> desiredState = new ConcurrentHashMap<>();

    void addDesiredState(ManagedKafka remoteManagedKafka) {
        desiredState.put(managedKafkaKey(remoteManagedKafka), remoteManagedKafka);
    }

    /**
     * Remove the desired state
     * @param remoteManagedKafka
     */
    public void removeDesiredState(ManagedKafka remoteManagedKafka) {
        desiredState.remove(managedKafkaKey(remoteManagedKafka));
    }

    /**
     * Get the latest known desired state of the managed kafka
     * @param managedKafkaKey
     * @return
     */
    public ManagedKafka getDesiredState(String managedKafkaKey) {
        return desiredState.get(managedKafkaKey);
    }

    /**
     * Get all known desired states.  May include orphan entries
     * not in {@link #getKafkaClusters()} and entries that have not
     * yet been created locally.
     */
    public Collection<ManagedKafka> getDesiredStates() {
        return desiredState.values();
    }

    /**
     * Get the string key that uniquely identifies this managed kafka placement
     * @param kafka
     * @return
     */
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
        log.debug("Updating agent status");
        executorService.execute(() -> {
            ManagedKafkaAgent localManagedKafkaAgent = localLookup.getLocalManagedKafkaAgent();
            if (localManagedKafkaAgent == null) {
                return;
            }
            ManagedKafkaAgentStatus status = localManagedKafkaAgent.getStatus();
            if (status == null) {
                // the control plane does not like null or empty status
                // so to avoid a warning/error in our logs, we'll just skip for now
                // as they are not looking for this sync yet as a heartbeat
                return;
            }
            controlPlaneClient.updateStatus(id, status);
        });
    }

    /**
     * Get the current list of ManagedKafka clusters from the control plane
     * as a blocking call.
     * Also updates the cache of desired state ManagedKafka instances.  May include
     * entries that have not yet been created locally.
     *
     * @see {@link #getDesiredStates()} to get the full cache, rather than making a
     * remote call
     */
    public List<ManagedKafka> getKafkaClusters() {
        ManagedKafkaList result = controlPlaneClient.getKafkaClusters(id);
        result.getItems().forEach((mk)->addDesiredState(mk));
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
    @Counted(value = "sync.resync", description = "The number of resync calls") // no need to be timed as the actions are async
    @Scheduled(every = "{resync.interval}", concurrentExecution = ConcurrentExecution.SKIP)
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
