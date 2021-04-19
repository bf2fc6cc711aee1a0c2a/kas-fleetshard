package org.bf2.sync.informer;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.bf2.common.ConditionUtils;
import org.bf2.common.ResourceInformer;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Type;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.sync.controlplane.ControlPlane;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.List;

@ApplicationScoped
public class InformerManager implements LocalLookup {

    @Inject
    KubernetesClient client;

    @Inject
    ControlPlane controlPlane;

    @Inject
    MeterRegistry meterRegistry;

    private SharedInformerFactory sharedInformerFactory;

    private ResourceInformer<ManagedKafka> managedKafkaInformer;
    private ResourceInformer<ManagedKafkaAgent> managedAgentInformer;

    @PostConstruct
    protected void onStart() {
        sharedInformerFactory = client.informers();

        managedKafkaInformer = new ResourceInformer<>(
                sharedInformerFactory.sharedIndexInformerFor(ManagedKafka.class, ManagedKafkaList.class,0),
                CustomResourceEventHandler.of(controlPlane::updateKafkaClusterStatus));

        // for the Agent
        managedAgentInformer = new ResourceInformer<>(
                sharedInformerFactory.sharedIndexInformerFor(ManagedKafkaAgent.class, ManagedKafkaAgentList.class,0),
                CustomResourceEventHandler.of(controlPlane::updateAgentStatus));

        sharedInformerFactory.startAllRegisteredInformers();

        meterRegistry.gauge("managedkafkas", this, (informer) -> {
            if (!informer.isReady()) {
                throw new IllegalStateException();
            }
            return informer.getLocalManagedKafkas().size();
        });

        meterRegistry.gauge("managedkafkas.ready", this, (informer) -> {
            if (!informer.isReady()) {
                throw new IllegalStateException();
            }
            return informer.getLocalManagedKafkas()
                    .stream()
                    .filter(mk -> mk.getStatus() != null && ConditionUtils
                            .findManagedKafkaCondition(mk.getStatus().getConditions(), Type.Ready)
                            .filter(mkc -> ManagedKafkaCondition.Status.True.name().equals(mkc.getStatus()))
                            .isPresent())
                    .count();
        });
    }

    @PreDestroy
    protected void onStop() {
        sharedInformerFactory.stopAllRegisteredInformers();
    }

    @Override
    public ManagedKafka getLocalManagedKafka(String metaNamespaceKey) {
        return managedKafkaInformer.getByKey(metaNamespaceKey);
    }

    @Override
    public List<ManagedKafka> getLocalManagedKafkas() {
        return managedKafkaInformer.getList();
    }

    static boolean hasLength(String value) {
        return value != null && !value.isEmpty();
    }

    @Override
    public boolean isReady() {
        return managedKafkaInformer.isReady()
                && managedAgentInformer.isReady();
    }

    @Override
    public ManagedKafkaAgent getLocalManagedKafkaAgent() {
        List<ManagedKafkaAgent> list = managedAgentInformer.getList();
        if (list.isEmpty()) {
            return null;
        }
        // we're assuming it's a singleton
        return list.get(0);
    }
}
