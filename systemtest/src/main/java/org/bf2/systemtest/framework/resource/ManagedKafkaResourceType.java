package org.bf2.systemtest.framework.resource;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.systemtest.framework.KeycloakInstance;
import org.bf2.systemtest.framework.SecurityUtils;
import org.bf2.systemtest.framework.SystemTestEnvironment;
import org.bf2.test.TestUtils;
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
    public static boolean hasConditionStatus(ManagedKafka mk, ManagedKafkaCondition.Type type, ManagedKafkaCondition.Status status) {
        if (mk == null) {
            return false;
        }
        return hasConditionStatus(mk.getStatus(), type, status);
    }

    public static boolean hasConditionStatus(ManagedKafkaStatus mks, ManagedKafkaCondition.Type type, ManagedKafkaCondition.Status status) {
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
     *
     * @throws Exception
     */
    public static ManagedKafka getDefault(String namespace, String appName, KeycloakInstance keycloak, String strimziVersion) throws Exception {
        final String tlsCert;
        final String tlsKey;

        if (SystemTestEnvironment.DUMMY_CERT.equals(SystemTestEnvironment.ENDPOINT_TLS_CERT)) {
            SecurityUtils.TlsConfig tlsConfig = SecurityUtils.getTLSConfig(SystemTestEnvironment.BOOTSTRAP_HOST_DOMAIN);
            tlsCert = tlsConfig.getCert();
            tlsKey = tlsConfig.getKey();
        } else {
            tlsCert = SystemTestEnvironment.ENDPOINT_TLS_CERT;
            tlsKey = SystemTestEnvironment.ENDPOINT_TLS_KEY;
        }

        final String oauthClientId;
        final String oauthTlsCert;
        final String oauthClientSecret;
        final String oauthUserClaim;
        final String oauthJwksEndpoint;
        final String oauthTokenEndpoint;
        final String oauthIssuerEndpoint;

        if (keycloak != null) {
            oauthClientId = "kafka";
            oauthTlsCert = keycloak.getKeycloakCert();
            oauthClientSecret = "kafka";
            oauthUserClaim = keycloak.getUserNameClaim();
            oauthJwksEndpoint = keycloak.getJwksEndpointUri();
            oauthTokenEndpoint = keycloak.getOauthTokenEndpointUri();
            oauthIssuerEndpoint = keycloak.getValidIssuerUri();
        } else if (SystemTestEnvironment.DUMMY_OAUTH_JWKS_URI.equals(SystemTestEnvironment.OAUTH_JWKS_ENDPOINT)) {
            oauthClientId = null;
            oauthTlsCert = null;
            oauthClientSecret = null;
            oauthUserClaim = null;
            oauthJwksEndpoint = null;
            oauthTokenEndpoint = null;
            oauthIssuerEndpoint = null;
        } else {
            //use defined values by env vars for oauth
            oauthClientId = SystemTestEnvironment.OAUTH_CLIENT_ID;
            oauthTlsCert = SystemTestEnvironment.DUMMY_CERT.equals(SystemTestEnvironment.OAUTH_TLS_CERT) ? null : SystemTestEnvironment.OAUTH_TLS_CERT;
            oauthClientSecret = SystemTestEnvironment.OAUTH_CLIENT_SECRET;
            oauthUserClaim = SystemTestEnvironment.OAUTH_USER_CLAIM;
            oauthJwksEndpoint = SystemTestEnvironment.OAUTH_JWKS_ENDPOINT;
            oauthTokenEndpoint = SystemTestEnvironment.OAUTH_TOKEN_ENDPOINT;
            oauthIssuerEndpoint = SystemTestEnvironment.OAUTH_ISSUER_ENDPOINT;
        }

        return ManagedKafka.getDefault(appName,
                namespace,
                SystemTestEnvironment.BOOTSTRAP_HOST_DOMAIN,
                tlsCert,
                tlsKey,
                oauthClientId,
                oauthTlsCert,
                oauthClientSecret,
                oauthUserClaim,
                oauthJwksEndpoint,
                oauthTokenEndpoint,
                oauthIssuerEndpoint,
                strimziVersion);
    }

    public static void isDeleted(ManagedKafka mk) {
        TestUtils.waitFor("Managed kafka is removed", 1_000, 600_000, () -> {
            ManagedKafka m = ManagedKafkaResourceType.getOperation().inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get();
            List<Pod> pods = KubeClient.getInstance().client().pods().inNamespace(mk.getMetadata().getNamespace()).list().getItems();
            return m == null && pods.size() == 0;
        });
    }
}
