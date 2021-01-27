package org.bf2.sync;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class InformerManager implements LocalLookup {

	@Inject
	KubernetesClient client;

	@Inject
	ManagedKafkaResourceEventHandler managedKafkaHandler;

	private SharedInformerFactory sharedInformerFactory;

	private SharedIndexInformer<ManagedKafka> managedKafkaInformer;

	void onStart(@Observes StartupEvent ev) {
		sharedInformerFactory = client.informers();

		// TODO: should be configurable
		int resyncPeriodInMillis = 60000;

		managedKafkaInformer = sharedInformerFactory.sharedIndexInformerFor(ManagedKafka.class, ManagedKafkaList.class,
				resyncPeriodInMillis);

		managedKafkaInformer.addEventHandler(managedKafkaHandler);

		managedKafkaInformer.run();
	}

	void onStop(@Observes ShutdownEvent ev) {
		sharedInformerFactory.stopAllRegisteredInformers();
	}

	public ManagedKafka getLocalManagedKafka(ManagedKafka remote) {
		return managedKafkaInformer.getIndexer().getByKey(Cache.metaNamespaceKeyFunc(remote));
	}
}