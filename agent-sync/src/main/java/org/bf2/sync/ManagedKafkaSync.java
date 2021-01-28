package org.bf2.sync;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.informer.LocalLookup;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

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
    KubernetesClient client;

    @Inject
    LocalLookup lookup;

    @Inject
    ControlPlane controlPlane;

    private MixedOperation<ManagedKafka, ManagedKafkaList, Resource<ManagedKafka>> managedKafkaResources;

    @PostConstruct
    void init() {
        CustomResourceDefinitionContext crdContext = CustomResourceDefinitionContext
                .fromCustomResourceType(ManagedKafka.class);

        managedKafkaResources = client.customResources(crdContext, ManagedKafka.class, ManagedKafkaList.class);
    }

    public void syncKafkaClusters(List<ManagedKafka> remoteManagedKafkas, Executor executor) {
        for (ManagedKafka remoteManagedKafka : remoteManagedKafkas) {
            ManagedKafkaSpec remoteSpec = remoteManagedKafka.getSpec();
            assert remoteSpec != null;

            // TODO: account for namespaces.
            // eventually the remote metadata should be ignored - and we should assign /
            // create a local namespace as needed
            String namespace = remoteManagedKafka.getMetadata().getNamespace();

            ManagedKafka existing = lookup.getLocalManagedKafka(remoteManagedKafka);

            // take action based upon differences
            // this is really just seeing if an instance needs created and the delete flag
            // there are no other fields to reconcile - but you could envision updating
            // component versions etc. later
            if (existing == null) {
                if (!remoteSpec.isDeleted()) {
                    // control plane should not have provided status
                    assert remoteManagedKafka.getStatus() == null;
                    // now that the namespace is set, start tracking
                    controlPlane.addManagedKafka(remoteManagedKafka);

                    executor.execute(() -> {
                        // TODO: create namespace when needed

                        managedKafkaResources.create(remoteManagedKafka);
                    });
                } else {
                    // we've successfully removed locally, but control plane is not aware
                    // we need to send another status update to let them know

                    // probably no longer needs to be async since the control plane call is async
                    executor.execute(() -> {
                        ManagedKafkaStatus status = new ManagedKafkaStatus();
                        ManagedKafkaCondition managedKafkaCondition = new ManagedKafkaCondition();
                        managedKafkaCondition.setType("InstanceDeletionComplete");
                        status.setConditions(Arrays.asList(managedKafkaCondition));
                        controlPlane.updateKafkaClusterStatus(status, remoteManagedKafka.getKafkaClusterId());
                    });
                }
            } else if (remoteSpec.isDeleted() && !existing.getSpec().isDeleted()) {
                controlPlane.removeManagedKafka(existing);
                // mark the local as deleted
                executor.execute(() -> {
                    managedKafkaResources.inNamespace(existing.getMetadata().getNamespace())
                            .withName(existing.getMetadata().getName()).edit(mk -> {
                                mk.getSpec().setDeleted(true);
                                return mk;
                            });
                    // the agent will handle the deletion from here
                });
            } else if (!remoteSpec.isDeleted() && existing.getSpec().isDeleted()) {
                // TODO: seems like a problem / resurrection
            }
        }
    }

}
