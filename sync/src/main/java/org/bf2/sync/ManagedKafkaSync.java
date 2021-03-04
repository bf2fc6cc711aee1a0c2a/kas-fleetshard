package org.bf2.sync;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.WebApplicationException;

import org.bf2.common.ConditionUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Type;
import org.bf2.sync.client.ManagedKafkaResourceClient;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.quarkus.scheduler.Scheduled;

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
    public void syncKafkaClusters() {
        Map<String, ManagedKafka> remotes = new HashMap<>();

        for (ManagedKafka remoteManagedKafka : controlPlane.getKafkaClusters()) {
            remotes.put(remoteManagedKafka.getId(), remoteManagedKafka);
            ManagedKafkaSpec remoteSpec = remoteManagedKafka.getSpec();
            assert remoteSpec != null;

            String localKey = Cache.namespaceKeyFunc(determineNamespace(remoteManagedKafka), remoteManagedKafka.getMetadata().getName());
            ManagedKafka existing = lookup.getLocalManagedKafka(localKey);

            // take action based upon differences
            // this is really just seeing if an instance needs created and the delete flag
            // there are no other fields to reconcile - but you could envision updating
            // component versions etc. later

            if (existing == null) {
                if (!remoteSpec.isDeleted()) {
                    reconcileAsync(remoteManagedKafka.getId(), localKey);
                } else {
                    // we've successfully removed locally, but control plane is not aware
                    // we need to send another status update to let them know

                    ManagedKafkaStatusBuilder statusBuilder = new ManagedKafkaStatusBuilder();
                    statusBuilder.withConditions(ConditionUtils.buildCondition(Type.Deleted, "True"));

                    // fire and forget the async call - if it fails, we'll retry on the next poll
                    controlPlane.updateKafkaClusterStatus(()->{return Map.of(remoteManagedKafka.getId(), statusBuilder.build());});
                }
            } else if (specChanged(remoteSpec, existing)) {
                reconcileAsync(remoteManagedKafka.getId(), localKey);
            }
        }

        // process final removals
        for (ManagedKafka local : lookup.getLocalManagedKafkas()) {
            if (remotes.get(local.getId()) != null) {
                continue;
            }

            // TODO: confirm full deletion
            // also check for condition or whatever is signaling done
            if (!local.getSpec().isDeleted()) {
                // TODO: seems bad
                log.warnf("Control plane wants to fully remove %s, but it's not marked as fully deleted", Cache.metaNamespaceKeyFunc(local));
            }

            reconcileAsync(null, Cache.metaNamespaceKeyFunc(local));
        }

    }

    /**
     * Determine what local namespace the remote instance should be in
     * For now we're assuming the (placement) id
     */
    String determineNamespace(ManagedKafka remoteManagedKafka) {
        return remoteManagedKafka.getId();
    }

    public static boolean specChanged(ManagedKafkaSpec remoteSpec, ManagedKafka existing) {
        if (!remoteSpec.isDeleted() && existing.getSpec().isDeleted()) {
            // TODO: seems like a problem / resurrection - should not happen
            log.warnf("Ignoring ManagedKafka %s that wants to come back to life", Cache.metaNamespaceKeyFunc(existing));
            return false;
        }

        return !remoteSpec.equals(existing);
    }

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
            delete(local);
        } else if (specChanged(remote.getSpec(), local)) {
            log.debugf("Updating ManagedKafka Spec for %s", Cache.metaNamespaceKeyFunc(remote));
            ManagedKafkaSpec spec = remote.getSpec();
            client.edit(local.getMetadata().getNamespace(), local.getMetadata().getName(), mk -> {
                    mk.setSpec(spec);
                    return mk;
                });
            // the operator will handle it from here
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
        String namespace = determineNamespace(remote);

        remote.getMetadata().setNamespace(namespace);

        // log after the namespace is set
        log.debugf("Creating ManagedKafka %s", Cache.metaNamespaceKeyFunc(remote));

        kubeClient.namespaces().createOrReplace(
                new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build());

        // TODO: there may be additional cleansing, setting of defaults, etc. on the remote instance before creation
        client.create(remote);
    }

    @Scheduled(every = "{poll.interval}")
    void pollKafkaClusters() {
        log.debug("Polling for control plane managed kafkas");
        // TODO: this is based upon a full poll - eventually this could be
        // based upon a delta revision / timestmap to get a smaller list
        executorService.execute(()->{
            try {
                syncKafkaClusters();
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException || e instanceof WebApplicationException) {
                    log.infof("Could not poll for managed kafkas %s", e.getMessage());
                } else {
                    log.errorf(e, "Could not poll for managed kafkas");
                }
            }
        });
    }

}
