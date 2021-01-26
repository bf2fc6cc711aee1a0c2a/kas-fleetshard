package org.bf2.sync;

import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

@ApplicationScoped
public class ManagedKafkaSync {
	
	@Inject
    KubernetesClient client;

	@Inject
	LocalLookup lookup;

	public void syncKafkaClusters(List<ManagedKafka> remoteManagedKafkas) {
		CustomResourceDefinitionContext crdContext = CustomResourceDefinitionContext.fromCustomResourceType(ManagedKafka.class);

		MixedOperation<ManagedKafka, ManagedKafkaList, Resource<ManagedKafka>> managedKafkaResources = client.customResources(crdContext, ManagedKafka.class, ManagedKafkaList.class);
		
		for (ManagedKafka remoteManagedKafka : remoteManagedKafkas) {
			ManagedKafkaSpec remoteSpec = remoteManagedKafka.getSpec();
			assert remoteSpec != null;
			
			ManagedKafka existing = lookup.getLocalManagedKafka(remoteManagedKafka);
			
			// take action based upon differences
			if (existing == null) {
				if (!remoteSpec.isDeleted()) {
					managedKafkaResources.create(remoteManagedKafka);
				}
			} else if (remoteSpec.isDeleted() && !existing.getSpec().isDeleted()) {
				//mark the local as deleted						
				existing.getSpec().setDeleted(true);
				managedKafkaResources.createOrReplace(existing);
			}
		}
	}
}
