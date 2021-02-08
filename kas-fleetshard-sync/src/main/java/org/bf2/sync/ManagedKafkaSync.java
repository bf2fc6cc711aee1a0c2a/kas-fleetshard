package org.bf2.sync;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
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

    @Inject
    Logger log;

    @Inject
    ManagedKafkaResourceClient client;

    @Inject
    LocalLookup lookup;

    @Inject
    ControlPlane controlPlane;

    @javax.annotation.Resource
    ManagedExecutor pollExecutor;

    @Inject
    KubernetesClient kubeClient;

    /**
     * Update the local state based upon the remote ManagedKafkas
     * The strategy here is to take a pass over the list and find any deferred work
     * Then execute that deferred work using the {@link ManagedExecutor} but with
     * a refresh of the state to ensure we're still acting appropriately.
     */
    public void syncKafkaClusters(Executor executor) {
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
                    executor.execute(() -> {
                        reconcile(remoteManagedKafka.getId(), localKey);
                    });
                } else {
                    // we've successfully removed locally, but control plane is not aware
                    // we need to send another status update to let them know

                    // doesn't need to be async as the control plane call is async
                    ManagedKafkaStatusBuilder statusBuilder = new ManagedKafkaStatusBuilder();
                    statusBuilder.addNewCondition().withType("InstanceDeletionComplete").endCondition();

                    // fire and forget - if it fails, we'll retry on the next poll
                    controlPlane.updateKafkaClusterStatus(statusBuilder.build(), remoteManagedKafka.getId());
                }
            } else if (specChanged(remoteSpec, existing)) {
                executor.execute(() -> {
                    reconcile(remoteManagedKafka.getId(), localKey);
                });
            }
        }

        // process final removals
        for (ManagedKafka local : lookup.getLocalManagedKafkas()) {
            if (remotes.get(local.getId()) != null) {
                continue;
            }

            // TODO: confirm full deletion
            // also check for condition
            if (!local.getSpec().isDeleted()) {
                // TODO: seems bad
            }

            executor.execute(() -> {
                reconcile(null, Cache.metaNamespaceKeyFunc(local));
            });
        }

    }

    /**
     * Determine what local namespace the remote instance should be in
     * For now we're assuming the (placement) id
     */
    String determineNamespace(ManagedKafka remoteManagedKafka) {
        return remoteManagedKafka.getId();
    }

    /**
     * TODO: delete is the only spec change we are currently tracking wrt modifications
     * it's also not clear if there are any fields in the spec that we
     * or the agent may fill out by default that should be retained,
     *
     * this will be generalized as needed
     */
    private boolean specChanged(ManagedKafkaSpec remoteSpec, ManagedKafka existing) {
        if (!remoteSpec.isDeleted() && existing.getSpec().isDeleted()) {
            // TODO: seems like a problem / resurrection - should not happen
            log.warnf("Ignoring ManagedKafka %s that wants to come back to life", Cache.metaNamespaceKeyFunc(existing));
            return false;
        }

        return remoteSpec.isDeleted() && !existing.getSpec().isDeleted();
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
            log.debugf("Initiating Delete of ManagedKafka %s", Cache.metaNamespaceKeyFunc(remote));
            // specChanged is only looking at delete currently, so mark the local as deleted
            client.edit(local.getMetadata().getNamespace(), local.getMetadata().getName(), mk -> {
                    mk.getSpec().setDeleted(true);
                    return mk;
                });
            // the operator will handle the deletion from here
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
        controlPlane.updateKafkaClusterStatus(new ManagedKafkaStatus(), "x");
        log.debug("Polling for control plane managed kafkas");
        // TODO: this is based upon a full poll - eventually this could be
        // based upon a delta revision / timestmap to get a smaller list
        syncKafkaClusters(pollExecutor);
    }

}
