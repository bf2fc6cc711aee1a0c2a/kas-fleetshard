package org.bf2.sync;

import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import io.fabric8.kubernetes.client.KubernetesClient;

@ApplicationScoped
public class ManagedKafkaSync {
	
	@Inject
    KubernetesClient client;

	@Inject
	LocalLookup lookup;

	public void syncKafkaClusters(List<ManagedKafka> remoteManagedKafkas) {

		for (ManagedKafka managedKafka : remoteManagedKafkas) {
			ManagedKafka existing = lookup.getLocalManagedKafka(managedKafka);
			
			// take action based upon differences
			if (existing == null) {
				//TODO: add
				throw new AssertionError();
			}
		}
	}
}
