package org.bf2.sync;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.quarkus.scheduler.Scheduled;
import org.bf2.common.ConditionUtils;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Type;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Has the responsibility of processing the remote list of ManagedKafka from the
 * control plane.
 *
 * Actual modifications to the kube ManagedKafka are added to a simple work
 * queue, but you could envision using a more involved approach
 * https://github.com/kubernetes/client-go/tree/master/examples/workqueue -
 * where tasks could be superseded, rate limited, etc.
 */
@ApplicationScoped
public class ManagedKafkaSync {
    private static Logger log = Logger.getLogger(ManagedKafkaSync.class);

    @Inject
    ManagedKafkaResourceClient client;

    @Inject
    LocalLookup lookup;

    @Inject
    ControlPlane controlPlane;

    @Inject
    KubernetesClient kubeClient;

    @Inject
    ExecutorService executorService;

    /**
     * Update the local state based upon the remote ManagedKafkas
     * The strategy here is to take a pass over the list and find any deferred work
     * Then execute that deferred work using the {@link ManagedExecutor} but with
     * a refresh of the state to ensure we're still acting appropriately.
     */
    @Timed(value = "sync.poll", extraTags = {"resource", "ManagedKafka"}, description = "The time spent processing polling calls")
    @Counted(value = "sync.poll", extraTags = {"resource", "ManagedKafka"}, description = "The number of polling calls")
    public void syncKafkaClusters() {
        Map<String, ManagedKafka> remotes = new HashMap<>();

        for (ManagedKafka remoteManagedKafka : controlPlane.getKafkaClusters()) {
            assert remoteManagedKafka.getId() != null;
            assert remoteManagedKafka.getPlacementId() != null;
            assert remoteManagedKafka.getMetadata().getNamespace() != null;

            remotes.put(ControlPlane.managedKafkaKey(remoteManagedKafka), remoteManagedKafka);
            ManagedKafkaSpec remoteSpec = remoteManagedKafka.getSpec();
            assert remoteSpec != null;

            String localKey = Cache.namespaceKeyFunc(remoteManagedKafka.getMetadata().getNamespace(), remoteManagedKafka.getMetadata().getName());
            ManagedKafka existing = lookup.getLocalManagedKafka(localKey);

            // take action based upon differences
            // this is really just seeing if an instance needs created and the delete flag
            // there are no other fields to reconcile - but you could envision updating
            // component versions etc. later

            if (existing == null) {
                if (!remoteSpec.isDeleted()) {
                    reconcileAsync(ControlPlane.managedKafkaKey(remoteManagedKafka), localKey);
                } else {
                    // we've successfully removed locally, but control plane is not aware
                    // we need to send another status update to let them know

                    ManagedKafkaStatusBuilder statusBuilder = new ManagedKafkaStatusBuilder();
                    statusBuilder.withConditions(ConditionUtils.buildCondition(Type.Ready, Status.False).reason(Reason.Deleted));
                    // fire and forget the async call - if it fails, we'll retry on the next poll
                    controlPlane.updateKafkaClusterStatus(()->{return Map.of(remoteManagedKafka.getId(), statusBuilder.build());});
                }
            } else if (specChanged(remoteSpec, existing) || !Objects.equals(existing.getPlacementId(), remoteManagedKafka.getPlacementId())) {
                reconcileAsync(ControlPlane.managedKafkaKey(remoteManagedKafka), localKey);
            }
        }

        // process final removals
        for (ManagedKafka local : lookup.getLocalManagedKafkas()) {
            if (remotes.get(ControlPlane.managedKafkaKey(local)) != null || !deleteAllowed(local)) {
                continue;
            }

            reconcileAsync(null, Cache.metaNamespaceKeyFunc(local));
        }

    }

    boolean deleteAllowed(ManagedKafka local) {
        if (local.getId() == null) {
            return false; // not a managed instance
        }
        if (!local.getSpec().isDeleted()) {
            if (local.getStatus() != null
                    && ConditionUtils.findManagedKafkaCondition(local.getStatus().getConditions(), Type.Ready)
                            .filter(c -> Reason.Rejected.name().equals(c.getReason())).isPresent()) {
                return true;
            }
            log.warnf("Control plane wants to fully remove %s, but it's not marked as fully deleted", Cache.metaNamespaceKeyFunc(local));
            return false;
        }
        return true;
    }

    public static boolean specChanged(ManagedKafkaSpec remoteSpec, ManagedKafka existing) {
        if (!remoteSpec.isDeleted() && existing.getSpec().isDeleted()) {
            // TODO: seems like a problem / resurrection - should not happen
            log.warnf("Ignoring ManagedKafka %s that wants to come back to life", Cache.metaNamespaceKeyFunc(existing));
            return false;
        }

        return !remoteSpec.equals(existing.getSpec());
    }

    /**
     * @param remoteId - obtained from {@link ControlPlane#managedKafkaKey(ManagedKafka)}
     * @param localMetaNamespaceKey - obtained from {@link Cache#namespaceKeyFunc(String, String)}
     */
    void reconcileAsync(String remoteId, String localMetaNamespaceKey) {
        executorService.execute(() -> {
            reconcile(remoteId, localMetaNamespaceKey);
        });
    }

    /**
     * Final sync processing of the remote vs. local state
     */
    void reconcile(String remoteId, String localMetaNamespaceKey) {
        ManagedKafka local = null;
        if (localMetaNamespaceKey != null) {
            //refresh the local
            local = lookup.getLocalManagedKafka(localMetaNamespaceKey);
        }

        ManagedKafka remote = null;
        if (remoteId != null) {
            //refresh the remote
            remote = controlPlane.getManagedKafka(remoteId);
        }

        if (local == null && remote == null) {
            return; //nothing to do
        }

        if (local == null) {
            if (!remote.getSpec().isDeleted()) {
                create(remote);
            }
        } else if (remote == null) {
            if (deleteAllowed(local)) {
                delete(local);
            }
        } else {
            if (!Objects.equals(local.getPlacementId(), remote.getPlacementId())) {
                log.debugf("Waiting for existing ManagedKafka %s to disappear before attempting next placement", local.getPlacementId());
                return;
            }
            if (specChanged(remote.getSpec(), local)) {
                log.debugf("Updating ManagedKafka Spec for %s", Cache.metaNamespaceKeyFunc(local));
                ManagedKafkaSpec spec = remote.getSpec();
                client.edit(local.getMetadata().getNamespace(), local.getMetadata().getName(), mk -> {
                        mk.setSpec(spec);
                        return mk;
                    });
                // the operator will handle it from here
            }
        }
    }

    void delete(ManagedKafka local) {
        log.debugf("Deleting ManagedKafka %s", Cache.metaNamespaceKeyFunc(local));

        client.delete(local.getMetadata().getNamespace(), local.getMetadata().getName());

        kubeClient.namespaces().withName(local.getMetadata().getNamespace()).delete();

        // only remove the local after we're fully cleaned up, so that
        // we'll keep retrying if there is a failure
        controlPlane.removeManagedKafka(local);
    }

    void create(ManagedKafka remote) {
        // log after the namespace is set
        log.debugf("Creating ManagedKafka %s", Cache.metaNamespaceKeyFunc(remote));

        kubeClient.namespaces().createOrReplace(
                new NamespaceBuilder()
                        .withNewMetadata()
                            .withName(remote.getMetadata().getNamespace())
                            .withLabels(Collections.singletonMap("observability-operator/scrape-logging", "true"))
                        .endMetadata()
                        .build());

        try {
            client.create(remote);
        } catch (KubernetesClientException e) {
            if (e.getStatus().getCode() != HttpURLConnection.HTTP_CONFLICT) {
                throw e;
            }
            log.infof("ManagedKafka %s already exists", Cache.metaNamespaceKeyFunc(remote));
        }
    }

    @Scheduled(every = "{poll.interval}", delayed = "5s")
    void pollKafkaClusters() {
        if (!lookup.isReady()) {
            log.debug("Not ready to poll, the lookup is not ready");
            return;
        }
        log.debug("Polling for control plane managed kafkas");
        // TODO: this is based upon a full poll - eventually this could be
        // based upon a delta revision / timestmap to get a smaller list
        executorService.execute(() -> syncKafkaClusters());
    }

}
