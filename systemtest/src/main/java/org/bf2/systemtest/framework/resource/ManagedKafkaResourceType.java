package org.bf2.systemtest.framework.resource;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.test.Environment;
import org.bf2.test.k8s.KubeClient;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;

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
        return "True".equals(getConditionStatus(mk, ManagedKafkaCondition.Type.Ready));
    }

    @Override
    public void refreshResource(ManagedKafka existing, ManagedKafka newResource) {
        existing.setMetadata(newResource.getMetadata());
        existing.setSpec(newResource.getSpec());
        existing.setStatus(newResource.getStatus());
    }

    public static String getConditionStatus(ManagedKafka mk, ManagedKafkaCondition.Type type) {
        if (mk == null || mk.getStatus() == null || mk.getStatus().getConditions() == null) {
            return null;
        }

        for (ManagedKafkaCondition condition : mk.getStatus().getConditions()) {
            if (type.name().equals(condition.getType())) {
                return condition.getStatus();
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
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "kafka")) &&
                        !pod.getMetadata().getName().contains("exporter")).collect(Collectors.toList());
    }

    public static List<Pod> getKafkaExporterPods(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "kafka-exporter"))).collect(Collectors.toList());
    }

    public static List<Pod> getZookeeperPods(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "zookeeper"))).collect(Collectors.toList());
    }

    public static Pod getAdminApiPod(ManagedKafka mk) {
        return KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems().stream().filter(pod ->
                pod.getMetadata().getName().contains(String.format("%s-%s", mk.getMetadata().getName(), "admin-server"))).findFirst().get();
    }

    /**
     * get common default managedkafka instance
     */
    public static ManagedKafka getDefault(String namespace, String appName) {
        return new ManagedKafkaBuilder()
                .withId(namespace)
                .withMetadata(
                        new ObjectMetaBuilder()
                                .withNamespace(namespace)
                                .withName(appName)
                                .build())
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewVersions()
                                    .withKafka("2.6.0")
                                    .withStrimzi("0.21.1")
                                .endVersions()
                                .withNewCapacity()
                                    .withNewIngressEgressThroughputPerSec("4Mi")
                                    .withNewMaxDataRetentionPeriod("P14D")
                                    .withNewMaxDataRetentionSize("100Gi")
                                    .withTotalMaxConnections(500)
                                    .withMaxPartitions(100)
                                .endCapacity()
                                .withNewEndpoint()
                                    .withNewBootstrapServerHost(appName)
                                    .withNewTls()
                                        .withNewCert(Environment.ENDPOINT_TLS_CERT)
                                        .withNewKey(Environment.ENDPOINT_TLS_KEY)
                                    .endTls()
                                .endEndpoint()
                                .withNewOauth()
                                    .withClientId(Environment.OAUTH_CLIENT_ID)
                                    .withNewTlsTrustedCertificate(Environment.OAUTH_TLS_CERT)
                                    .withClientSecret(Environment.OAUTH_CLIENT_SECRET)
                                    .withUserNameClaim(Environment.OAUTH_USER_CLAIM)
                                    .withNewJwksEndpointURI(Environment.OAUTH_JWKS_ENDPOINT)
                                    .withNewTokenEndpointURI(Environment.OAUTH_TOKEN_ENDPOINT)
                                    .withNewValidIssuerEndpointURI(Environment.OAUTH_ISSUER_ENDPOINT)
                                .endOauth()
                                .build())
                .build();
    }
}
