package org.bf2.sync;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
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
    KubernetesClient client;

    @Inject
    LocalLookup lookup;

    @Inject
    ControlPlane controlPlane;

    @javax.annotation.Resource
    ManagedExecutor pollExecutor;

    private MixedOperation<ManagedKafka, ManagedKafkaList, Resource<ManagedKafka>> managedKafkaResources;

    @PostConstruct
    void init() {
        CustomResourceDefinitionContext crdContext = CustomResourceDefinitionContext
                .fromCustomResourceType(ManagedKafka.class);

        managedKafkaResources = client.customResources(crdContext, ManagedKafka.class, ManagedKafkaList.class);
    }

    /**
     * Update the local state based upon the remote ManagedKafkas
     * The strategy here is to take a pass over the list and find any deferred work
     * Then execute that deferred work using the {@link ManagedExecutor} but with
     * a refresh of the state to ensure we're still acting appropriately.
     */
    public void syncKafkaClusters(List<ManagedKafka> remoteManagedKafkas, Executor executor) {
        Map<String, ManagedKafka> remotes = new HashMap<>();

        for (ManagedKafka remoteManagedKafka : remoteManagedKafkas) {
            remotes.put(remoteManagedKafka.getId(), remoteManagedKafka);
            ManagedKafkaSpec remoteSpec = remoteManagedKafka.getSpec();
            assert remoteSpec != null;

            // TODO this requires the namespace is already set - this will change
            // see also the create method
            ManagedKafka existing = lookup.getLocalManagedKafka(Cache.metaNamespaceKeyFunc(remoteManagedKafka));

            // take action based upon differences
            // this is really just seeing if an instance needs created and the delete flag
            // there are no other fields to reconcile - but you could envision updating
            // component versions etc. later
            if (existing == null) {
                if (!remoteSpec.isDeleted()) {
                    controlPlane.addManagedKafka(remoteManagedKafka);

                    executor.execute(() -> {
                        reconcile(remoteManagedKafka.getId(), Cache.metaNamespaceKeyFunc(remoteManagedKafka));
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
            } else if (remoteSpec.isDeleted() && !existing.getSpec().isDeleted()) {
                executor.execute(() -> {
                    reconcile(remoteManagedKafka.getId(), Cache.metaNamespaceKeyFunc(existing));
                });
            } else if (!remoteSpec.isDeleted() && existing.getSpec().isDeleted()) {
                // TODO: seems like a problem / resurrection - should not happen
                log.warnf("Ignoring ManagedKafka %s %s that wants to come back to life", existing.getId(), Cache.metaNamespaceKeyFunc(existing));
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
            log.debugf("Deleting ManagedKafka %s %s", local.getId(), Cache.metaNamespaceKeyFunc(local));

            managedKafkaResources.inNamespace(local.getMetadata().getNamespace())
                    .withName(local.getMetadata().getName()).delete();
        } else if (remote.getSpec().isDeleted() && !local.getSpec().isDeleted()) {
            log.debugf("Initiating Delete of ManagedKafka %s %s", remote.getId(), Cache.metaNamespaceKeyFunc(remote));
            // mark the local as deleted
            managedKafkaResources.inNamespace(local.getMetadata().getNamespace())
                    .withName(local.getMetadata().getName()).edit(mk -> {
                        mk.getSpec().setDeleted(true);
                        return mk;
                    });
            controlPlane.removeManagedKafka(local);
            // the operator will handle the deletion from here
        }
    }

    void create(ManagedKafka remote) {
        // TODO: account for namespaces.
        // eventually the remote metadata should be ignored (or unset) - and we should assign /
        // create a local namespace as needed
        // for now we're assuming remote.getMetadata().getNamespace() is set;

        log.debugf("Creating ManagedKafka %s %s", remote.getId(), Cache.metaNamespaceKeyFunc(remote));

        // TODO: there may be additional cleansing, setting of defaults, etc. on the remote instance before creation
        managedKafkaResources.create(remote);
    }

    @Scheduled(every = "{poll.interval}")
    void pollKafkaClusters() {
        log.debug("Polling for control plane managed kafkas");
        // TODO: this is based upon a full poll - eventually this could be
        // based upon a delta revision / timestmap to get a smaller list
        syncKafkaClusters(controlPlane.getKafkaClusters(), pollExecutor);
    }

}
