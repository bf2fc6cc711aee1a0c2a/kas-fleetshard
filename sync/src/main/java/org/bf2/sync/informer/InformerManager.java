package org.bf2.sync.informer;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import org.bf2.common.ConditionUtils;
import org.bf2.common.OperandUtils;
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
    private ResourceInformer<Secret> secretInformer;

    @PostConstruct
    protected void onStart() {
        managedKafkaInformer = resourceInformerFactory.create(ManagedKafka.class, client.resources(ManagedKafka.class).inAnyNamespace(),
                CustomResourceEventHandler.of(controlPlane::updateKafkaClusterStatus));

        // for the Agent
        managedAgentInformer = resourceInformerFactory.create(ManagedKafkaAgent.class, client.resources(ManagedKafkaAgent.class).inNamespace(client.getNamespace()),
                CustomResourceEventHandler.of(controlPlane::updateAgentStatus));

        secretInformer = resourceInformerFactory.create(Secret.class, client.secrets().inAnyNamespace().withLabels(OperandUtils.getMasterSecretLabel()),
                null);

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

    public Secret getLocalSecret(String namespace, String name) {
        return secretInformer.getByKey(Cache.namespaceKeyFunc(namespace, name));
    }
}
