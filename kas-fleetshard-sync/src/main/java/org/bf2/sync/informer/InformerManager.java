package org.bf2.sync.informer;

import java.time.Duration;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
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

    @PostConstruct
    protected void onStart() {
        sharedInformerFactory = client.informers();

        managedKafkaInformer = sharedInformerFactory.sharedIndexInformerFor(ManagedKafka.class, ManagedKafkaList.class,
                resync.toMillis());
        managedKafkaInformer.addEventHandler(CustomResourceEventHandler.of(controlPlane::updateKafkaClusterStatus));

        // for the Agent
        managedAgentInformer = sharedInformerFactory.sharedIndexInformerFor(ManagedKafkaAgent.class, ManagedKafkaAgentList.class,
                resync.toMillis());
        managedAgentInformer.addEventHandler(CustomResourceEventHandler.of(controlPlane::updateStatus));

        sharedInformerFactory.startAllRegisteredInformers();
    }

    @PreDestroy
    protected void onStop() {
        sharedInformerFactory.stopAllRegisteredInformers();
    }

    @Override
    public ManagedKafka getLocalManagedKafka(String metaNamespaceKey) {
        return managedKafkaInformer.getIndexer().getByKey(metaNamespaceKey);
    }

    @Override
    public List<ManagedKafka> getLocalManagedKafkas() {
        return managedKafkaInformer.getIndexer().list();
    }

    public boolean isReady() {
        return managedAgentInformer.hasSynced() && managedAgentInformer.hasSynced();
    }
}