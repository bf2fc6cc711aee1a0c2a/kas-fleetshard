package org.bf2.sync;

import java.util.List;
import java.util.concurrent.Executor;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

/**
 * Has the responsibility of processing the remote list of ManagedKafka from the
 * control plane.
 *
 * Actual modifications to the local ManagedKafka are added to a simple work queue.
 * For now that is just the common pool, but you could envision using a more
 * involved approach https://github.com/kubernetes/client-go/tree/master/examples/workqueue
 * - where tasks could be superseded, rate limited, etc.
 */
@ApplicationScoped
public class ManagedKafkaSync {

	@Inject
    KubernetesClient client;

	@Inject
	LocalLookup lookup;

	public void syncKafkaClusters(List<ManagedKafka> remoteManagedKafkas, Executor executor) {
		CustomResourceDefinitionContext crdContext = CustomResourceDefinitionContext.fromCustomResourceType(ManagedKafka.class);

		MixedOperation<ManagedKafka, ManagedKafkaList, Resource<ManagedKafka>> managedKafkaResources = client.customResources(crdContext, ManagedKafka.class, ManagedKafkaList.class);

		for (ManagedKafka remoteManagedKafka : remoteManagedKafkas) {
			ManagedKafkaSpec remoteSpec = remoteManagedKafka.getSpec();
			assert remoteSpec != null;

			//TODO: account for namespaces.
			//eventually the remote metadata should be ignored - and we should assign / create a local namespace as needed
			String namespace = remoteManagedKafka.getMetadata().getNamespace();

			//there are two strategies - use the informer or we can do direct lookups when needed.
			//ManagedKafka existing = managedKafkaResources.inNamespace(namespace).withName(remoteManagedKafka.getMetadata().getName()).get();

			//using the informer seems to be the right approach as it should generally be up-to-date
			//and then we can defer any work queuing
			ManagedKafka existing = lookup.getLocalManagedKafka(remoteManagedKafka);

			// take action based upon differences
			// this is really just seeing if an instance needs created and the delete flag
			// there are no other fields to reconcile - but you could envision updating component versions etc. later
			if (existing == null) {
				if (!remoteSpec.isDeleted()) {
					//control plane should not have provided status
					assert remoteManagedKafka.getStatus() == null;

					executor.execute(()-> {
						//create namespace when needed

						managedKafkaResources.create(remoteManagedKafka);
					});
				} else {
					//we've successfully removed locally, but control plane is not aware
					//we need to send another status update to let them know

					executor.execute(()-> {
						throw new AssertionError("TODO: implement me");
					});
				}
			} else if (remoteSpec.isDeleted() && !existing.getSpec().isDeleted()) {
				//mark the local as deleted
				executor.execute(()-> {
					//TODO: directly modifying the store object may not be a good idea
					existing.getSpec().setDeleted(true);
					managedKafkaResources.createOrReplace(existing);
					//the agent will handle the deletion from here
				});
			} else if (!remoteSpec.isDeleted() && existing.getSpec().isDeleted()) {
				//TODO: seems like a problem / resurrection
			}
		}
	}

}
