package org.bf2.sync.controlplane;

import static java.util.Objects.requireNonNullElse;

import java.util.Collections;
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
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ControlPlane {

    private static final ManagedKafkaStatus EMPTY_MANAGED_KAFKA_STATUS = new ManagedKafkaStatus();
    private static final ManagedKafkaAgentStatus EMPTY_MANAGED_KAFKA_AGENT_STATUS = new ManagedKafkaAgentStatus();

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
     * Make an async call to update the status, but only if it is different that the old.
     *
     * newAgent is expected to be non-null
     */
    public void updateAgentStatus(ManagedKafkaAgent oldAgent, ManagedKafkaAgent newAgent) {
        if (oldAgent != null && statusChanged(oldAgent.getStatus(), newAgent.getStatus())) {
            // send a status update immediately (async)
            updateAgentStatus();
        }
    }

    private void updateAgentStatus() {
        executorService.execute(() -> {
            try {
                ManagedKafkaAgent localManagedKafkaAgent = localLookup.getLocalManagedKafkaAgent();
                if (localManagedKafkaAgent != null) {
                    controlPlaneClient.updateStatus(id, localManagedKafkaAgent.getStatus());
                }
                // TODO if it's null we could still send an empty status
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
     * Async update the control plane with the status of this ManagedKafka, but
     * only if it's different than the old
     *
     * newManagedKafka is expected to be non-null as deletes are not processed
     */
    public void updateKafkaClusterStatus(ManagedKafka oldManagedKafka, ManagedKafka newManagedKafka) {
        if (oldManagedKafka != null && statusChanged(oldManagedKafka.getStatus(), newManagedKafka.getStatus())) {
            // send a status update immediately (async)
            updateKafkaClusterStatus(Cache.metaNamespaceKeyFunc(newManagedKafka), newManagedKafka.getId());
        }
    }

    static boolean statusChanged(ManagedKafkaAgentStatus oldStatus, ManagedKafkaAgentStatus newStatus) {
        // TODO this will likely change as we're reusing the ManagedKafkaConditions
        // a couple of interfaces would keep the logic similar though
        return maxTransitionTime(requireNonNullElse(oldStatus, EMPTY_MANAGED_KAFKA_AGENT_STATUS).getConditions()).compareTo(
                maxTransitionTime(requireNonNullElse(newStatus, EMPTY_MANAGED_KAFKA_AGENT_STATUS).getConditions())) < 0;
    }

    static boolean statusChanged(ManagedKafkaStatus oldStatus, ManagedKafkaStatus newStatus) {
        return maxTransitionTime(requireNonNullElse(oldStatus, EMPTY_MANAGED_KAFKA_STATUS).getConditions()).compareTo(
                maxTransitionTime(requireNonNullElse(newStatus, EMPTY_MANAGED_KAFKA_STATUS).getConditions())) < 0;
    }

    private static String maxTransitionTime(List<ManagedKafkaCondition> conditions) {
        return requireNonNullElse(conditions, Collections.<ManagedKafkaCondition>emptyList()).stream()
                        .map((mkc)->requireNonNullElse(mkc.getLastTransitionTime(), "0"))
                        .max(String::compareTo).orElse("");
    }

    /**
     * On the resync interval, send everything
     */
    @Scheduled(every = "{resync.interval}", delayed = "{update.delayed}")
    public void sendResync() {
        updateKafkaClusterStatus(() -> {
            return localLookup.getLocalManagedKafkas().stream().collect(Collectors.toMap(ManagedKafka::getId,
                    (mk) -> requireNonNullElse(mk.getStatus(), EMPTY_MANAGED_KAFKA_STATUS)));
        });
        updateAgentStatus();
    }

}
