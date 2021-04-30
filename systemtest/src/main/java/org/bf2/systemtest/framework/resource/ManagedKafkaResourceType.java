package org.bf2.systemtest.framework.resource;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.test.Environment;
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
        getOperation().inNamespace(resource.getMetadata().getNamespace())
                .withName(resource.getMetadata().getName())
                .delete();
    }

    @Override
    public boolean isReady(ManagedKafka mk) {
        return hasConditionStatus(mk, ManagedKafkaCondition.Type.Ready, ManagedKafkaCondition.Status.True);
    }

    @Override
    public void refreshResource(ManagedKafka existing, ManagedKafka newResource) {
        existing.setMetadata(newResource.getMetadata());
        existing.setSpec(newResource.getSpec());
        existing.setStatus(newResource.getStatus());
    }

    /**
     * @return true if and only if there is a condition of the given type with the given status
     */
    public static boolean hasConditionStatus(ManagedKafka mk, ManagedKafkaCondition.Type type,
            ManagedKafkaCondition.Status status) {
        if (mk == null) {
            return false;
        }
        return hasConditionStatus(mk.getStatus(), type, status);
    }

    public static boolean hasConditionStatus(ManagedKafkaStatus mks, ManagedKafkaCondition.Type type,
            ManagedKafkaCondition.Status status) {
        if (mks == null || mks.getConditions() == null) {
            return false;
        }
        for (ManagedKafkaCondition condition : mks.getConditions()) {
            if (type.name().equals(condition.getType())) {
                return status.name().equals(condition.getStatus());
            }
        }
        return false;
    }

    public static Pod getCanaryPod(ManagedKafka mk) {
        return KubeClient.getInstance()
                .client()
                .pods()
                .inNamespace(mk.getMetadata().getNamespace())
                .list()
                .getItems()
                .stream()
                .filter(pod -> pod.getMetadata()
                        .getName()
                        .contains(String.format("%s-%s", mk.getMetadata().getName(), "canary")))
                .findFirst()
                .get();
    }

    public static List<Pod> getKafkaPods(ManagedKafka mk) {
        return KubeClient.getInstance()
                .client()
                .pods()
                .inNamespace(mk.getMetadata().getNamespace())
                .list()
                .getItems()
                .stream()
                .filter(pod -> pod.getMetadata()
                        .getName()
                        .contains(String.format("%s-%s", mk.getMetadata().getName(), "kafka")) &&
                        !pod.getMetadata().getName().contains("exporter"))
                .collect(Collectors.toList());
    }

    public static List<Pod> getKafkaExporterPods(ManagedKafka mk) {
        return KubeClient.getInstance()
                .client()
                .pods()
                .inNamespace(mk.getMetadata().getNamespace())
                .list()
                .getItems()
                .stream()
                .filter(pod -> pod.getMetadata()
                        .getName()
                        .contains(String.format("%s-%s", mk.getMetadata().getName(), "kafka-exporter")))
                .collect(Collectors.toList());
    }

    public static List<Pod> getZookeeperPods(ManagedKafka mk) {
        return KubeClient.getInstance()
                .client()
                .pods()
                .inNamespace(mk.getMetadata().getNamespace())
                .list()
                .getItems()
                .stream()
                .filter(pod -> pod.getMetadata()
                        .getName()
                        .contains(String.format("%s-%s", mk.getMetadata().getName(), "zookeeper")))
                .collect(Collectors.toList());
    }

    public static Pod getAdminApiPod(ManagedKafka mk) {
        return KubeClient.getInstance()
                .client()
                .pods()
                .inNamespace(mk.getMetadata().getNamespace())
                .list()
                .getItems()
                .stream()
                .filter(pod -> pod.getMetadata()
                        .getName()
                        .contains(String.format("%s-%s", mk.getMetadata().getName(), "admin-server")))
                .findFirst()
                .get();
    }

    /**
     * get common default managedkafka instance
     */
    public static ManagedKafka getDefault(String namespace, String appName) {
        return ManagedKafka.getDefault(appName, namespace, Environment.BOOTSTRAP_HOST_DOMAIN,
                Environment.ENDPOINT_TLS_CERT, Environment.ENDPOINT_TLS_KEY, Environment.OAUTH_CLIENT_ID,
                Environment.OAUTH_TLS_CERT, Environment.OAUTH_CLIENT_SECRET, Environment.OAUTH_USER_CLAIM,
                Environment.OAUTH_JWKS_ENDPOINT, Environment.OAUTH_TOKEN_ENDPOINT, Environment.OAUTH_ISSUER_ENDPOINT);
    }
}
