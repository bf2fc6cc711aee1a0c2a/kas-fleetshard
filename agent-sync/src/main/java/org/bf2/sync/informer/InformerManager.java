package org.bf2.sync.informer;

import java.time.Duration;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.sync.controlplane.ControlPlane;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class InformerManager implements LocalLookup {

    @ConfigProperty(name = "resync.interval")
    Duration resync;

    @Inject
    KubernetesClient client;

    @Inject
    ControlPlane controlPlane;

    private SharedInformerFactory sharedInformerFactory;

    private SharedIndexInformer<ManagedKafka> managedKafkaInformer;
    private SharedIndexInformer<ManagedKafkaAgent> managedAgentInformer;

    void onStart(@Observes StartupEvent ev) {
        sharedInformerFactory = client.informers();

        managedKafkaInformer = sharedInformerFactory.sharedIndexInformerFor(ManagedKafka.class, ManagedKafkaList.class,
                resync.toMillis());

        managedKafkaInformer.addEventHandler(new CustomResourceEventHandler<ManagedKafka>(controlPlane::updateKafkaClusterStatus));

        managedKafkaInformer.run();

        // for the Agent
        managedAgentInformer = sharedInformerFactory.sharedIndexInformerFor(ManagedKafkaAgent.class, ManagedKafkaAgentList.class,
                resync.toMillis());
        managedAgentInformer.addEventHandler(new CustomResourceEventHandler<ManagedKafkaAgent>(controlPlane::updateStatus));
        managedAgentInformer.run();
    }

    void onStop(@Observes ShutdownEvent ev) {
        sharedInformerFactory.stopAllRegisteredInformers();
    }

    @Override
    public ManagedKafka getLocalManagedKafka(ManagedKafka remote) {
        return managedKafkaInformer.getIndexer().getByKey(Cache.metaNamespaceKeyFunc(remote));
    }

    @Override
    public List<ManagedKafka> getLocalManagedKafkas() {
        return managedKafkaInformer.getIndexer().list();
    }
}