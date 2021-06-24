package org.bf2.sync.informer;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import org.bf2.common.ConditionUtils;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Type;
import org.bf2.sync.controlplane.ControlPlane;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.List;

@Startup
@ApplicationScoped
public class InformerManager implements LocalLookup {

    @Inject
    KubernetesClient client;

    @Inject
    ControlPlane controlPlane;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    private ResourceInformer<ManagedKafka> managedKafkaInformer;
    private ResourceInformer<ManagedKafkaAgent> managedAgentInformer;

    @PostConstruct
    protected void onStart() {
        managedKafkaInformer = resourceInformerFactory.start(ManagedKafka.class, client.customResources(ManagedKafka.class).inAnyNamespace(),
                CustomResourceEventHandler.of(controlPlane::updateKafkaClusterStatus));

        // for the Agent
        managedAgentInformer = resourceInformerFactory.start(ManagedKafkaAgent.class, client.customResources(ManagedKafkaAgent.class).inAnyNamespace(),
                CustomResourceEventHandler.of(controlPlane::updateAgentStatus));

        meterRegistry.gauge("managedkafkas", this, (informer) -> {
            return informer.getLocalManagedKafkas().size();
        });

        meterRegistry.gauge("managedkafkas.ready", this, (informer) -> {
            return informer.getLocalManagedKafkas()
                    .stream()
                    .filter(mk -> mk.getStatus() != null && ConditionUtils
                            .findManagedKafkaCondition(mk.getStatus().getConditions(), Type.Ready)
                            .filter(mkc -> ManagedKafkaCondition.Status.True.name().equals(mkc.getStatus()))
                            .isPresent())
                    .count();
        });
    }

    @Override
    public ManagedKafka getLocalManagedKafka(String metaNamespaceKey) {
        return managedKafkaInformer.getByKey(metaNamespaceKey);
    }

    @Override
    public List<ManagedKafka> getLocalManagedKafkas() {
        return managedKafkaInformer.getList();
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
