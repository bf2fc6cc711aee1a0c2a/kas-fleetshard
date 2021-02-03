package org.bf2.systemtest.framework.resource;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.test.k8s.KubeClient;

import java.util.List;
import java.util.stream.Collectors;

public class ManagedKafkaResourceType implements ResourceType<ManagedKafka> {

    @Override
    public String getKind() {
        return ResourceKind.MANAGED_KAFKA;
    }

    @Override
    public ManagedKafka get(String namespace, String name) {
        return getOperation().inNamespace(namespace).withName(name).get();
    }

    public static MixedOperation<ManagedKafka, KubernetesResourceList<ManagedKafka>, Resource<ManagedKafka>> getOperation() {
        return KubeClient.getInstance().client().customResources(ManagedKafka.class);
    }

    @Override
    public void create(ManagedKafka resource) {
        getOperation().inNamespace(resource.getMetadata().getNamespace()).createOrReplace(resource);
    }

    @Override
    public void delete(ManagedKafka resource) throws InterruptedException {
        getOperation().inNamespace(resource.getMetadata().getNamespace()).withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public boolean isReady(ManagedKafka mk) {
        return mk != null &&
                mk.getStatus() != null &&
                mk.getStatus().getConditions().stream().anyMatch(c -> c.getType().equals(ManagedKafkaCondition.Type.Ready.name())) &&
                mk.getStatus().getConditions().stream().filter(c -> c.getType().equals(ManagedKafkaCondition.Type.Ready.name())).findFirst().get().getStatus().equals("True");
    }

    @Override
    public void refreshResource(ManagedKafka existing, ManagedKafka newResource) {
        existing.setMetadata(newResource.getMetadata());
        existing.setSpec(newResource.getSpec());
        existing.setStatus(newResource.getStatus());
    }

    public static ManagedKafkaCondition getCondition(List<ManagedKafkaCondition> conditions, ManagedKafkaCondition.Type type) {
        for (ManagedKafkaCondition condition : conditions) {
            if (type.name().equals(condition.getType())) {
                return condition;
            }
        }
        return null;
    }

    public static Pod getCanaryPod(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "canary"))).findFirst().get();
    }

    public static List<Pod> getKafkaPods(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "kafka"))).collect(Collectors.toList());
    }

    public static List<Pod> getZookeeperPods(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "zookeeper"))).collect(Collectors.toList());
    }
}
