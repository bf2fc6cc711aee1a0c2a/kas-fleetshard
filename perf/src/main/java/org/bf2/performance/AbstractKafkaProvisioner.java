package org.bf2.performance;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.performance.k8s.KubeClusterResource;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction for managing strimzi and kafka.
 */
public abstract class AbstractKafkaProvisioner implements KafkaProvisioner {
    protected KubeClusterResource cluster;
    protected List<RouterConfig> routerConfigs;

    AbstractKafkaProvisioner(KubeClusterResource cluster) {
        this.cluster = cluster;
    }

    @Override
    public void setup() throws Exception {
        this.routerConfigs = KafkaInstaller.createIngressControllers(cluster, Environment.NUM_INGRESS_CONTROLLERS);
    }

    @Override
    public void teardown() throws Exception {
        KafkaInstaller.deleteIngressController(cluster);
    }

    @Override
    public KubeClusterResource getKubernetesCluster() {
        return cluster;
    }

    protected void awaitKafkaDeploymentRemoval(MixedOperation<ManagedKafka, KubernetesResourceList<ManagedKafka>, Resource<ManagedKafka>> client, ManagedKafka k) {
        try {
            client.inNamespace(k.getMetadata().getNamespace()).withName(k.getMetadata().getName()).waitUntilCondition(Objects::isNull, 600, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
