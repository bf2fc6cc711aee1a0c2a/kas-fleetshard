package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.BuildableReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a ManagedKafka instance declaration with corresponding specification and status
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        refs = @BuildableReference(CustomResource.class),
        editableEnabled = false
)
@Group("managedkafka.bf2.org")
@Version("v1alpha1")
public class ManagedKafka extends CustomResource<ManagedKafkaSpec, ManagedKafkaStatus> implements Namespaced {

    public static final String RESERVED_DEPLOYMENT_TYPE = "reserved";

    private static final String CERT = "cert";

    public static final String BF2_DOMAIN = "bf2.org/";
    public static final String ID = BF2_DOMAIN + "id";
    public static final String PLACEMENT_ID = BF2_DOMAIN + "placementId";

    public static final String PROFILE_TYPE = BF2_DOMAIN + "kafkaInstanceProfileType";
    public static final String PROFILE_QUOTA_CONSUMED = BF2_DOMAIN + "kafkaInstanceProfileQuotaConsumed";

    public static final String DEPLOYMENT_TYPE = BF2_DOMAIN + "deployment";

    @Override
    protected ManagedKafkaSpec initSpec() {
        return new ManagedKafkaSpec();
    }

    /**
     * A null value will be treated as empty instead
     */
    @Override
    public void setSpec(ManagedKafkaSpec spec) {
        if (spec == null) {
            spec = initSpec();
        }
        super.setSpec(spec);
    }

    @JsonIgnore
    public String getId() {
        return getOrCreateAnnotations().get(ID);
    }

    private Map<String, String> getOrCreateAnnotations() {
        ObjectMeta metadata = getMetadata();
        if (metadata.getAnnotations() == null) {
            metadata.setAnnotations(new LinkedHashMap<>());
        }
        return metadata.getAnnotations();
    }

    public void setId(String id) {
        getOrCreateAnnotations().put(ID, id);
    }

    @JsonIgnore
    public String getPlacementId() {
        return getOrCreateAnnotations().get(PLACEMENT_ID);
    }

    public void setPlacementId(String placementId) {
        getOrCreateAnnotations().put(PLACEMENT_ID, placementId);
    }

    /**
     * Get a specific service account information from the ManagedKafka instance
     *
     * @param name name/type of service account to look for
     * @return service account related information
     */
    public Optional<ServiceAccount> getServiceAccount(ServiceAccount.ServiceAccountName name) {
        List<ServiceAccount> serviceAccounts = this.spec.getServiceAccounts();
        if (serviceAccounts != null && !serviceAccounts.isEmpty()) {
            Optional<ServiceAccount> serviceAccount =
                    serviceAccounts.stream()
                            .filter(sa -> name.toValue().equals(sa.getName()))
                            .findFirst();
            return serviceAccount;
        }
        return Optional.empty();
    }

    /**
     * Get a specific annotation value on the current ManagedKafka instance
     *
     * @param annotation annotation to look for in the current ManagedKafka instance metadata
     * @return annotation value or empty if not present
     */
    public Optional<String> getAnnotation(String annotation) {
        return Optional.ofNullable(this.getMetadata().getAnnotations())
                        .map(annotations -> annotations.get(annotation));
    }

    /**
     * Effectively a template for creating default {@link ManagedKafka} instances.
     */
    public static ManagedKafka getDefault(String name, String namespace, String bootstrapHostDomain,
            String endpointTlsCert, String endpointTlsKey, String oauthClientId, String oauthTlsCert,
            String oauthClientSecret, String oauthUserClaim, String oauthFallbackUserClaim, String oauthJwksEndpoint,
            String oauthTokenEndpoint, String oauthIssuerEndpoint, String strimziVersion, String kafkaVersion) {

        ManagedKafkaAuthenticationOAuth oauth = null;
        TlsKeyPair tls = null;

        if (endpointTlsCert != null && endpointTlsKey != null) {
            tls = new TlsKeyPairBuilder()
                    .withCert(endpointTlsCert)
                    .withKey(endpointTlsKey)
                    .build();
        }

        if (oauthClientId != null || oauthJwksEndpoint != null) {
            oauth = new ManagedKafkaAuthenticationOAuthBuilder()
                    .withClientId(oauthClientId)
                    .withTlsTrustedCertificate(oauthTlsCert)
                    .withClientSecret(oauthClientSecret)
                    .withUserNameClaim(oauthUserClaim)
                    .withFallbackUserNameClaim(oauthFallbackUserClaim)
                    .withJwksEndpointURI(oauthJwksEndpoint)
                    .withTokenEndpointURI(oauthTokenEndpoint)
                    .withValidIssuerEndpointURI(oauthIssuerEndpoint)
                    .build();
        }

        return new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withNamespace(namespace)
                    .withName(name)
                    .addToAnnotations(ID, UUID.randomUUID().toString())
                    .addToAnnotations(PLACEMENT_ID, name)
                .endMetadata()
                .withSpec(new ManagedKafkaSpecBuilder()
                        .withNewVersions()
                            .withKafka(kafkaVersion)
                            .withStrimzi(strimziVersion)
                            .endVersions()
                        .withNewCapacity()
                            .withNewIngressEgressThroughputPerSec("4Mi")
                            .withMaxDataRetentionPeriod("P14D")
                            .withNewMaxDataRetentionSize("100Gi")
                            .withTotalMaxConnections(500)
                            .withMaxPartitions(100)
                            .endCapacity()
                        .withNewEndpoint()
                            .withBootstrapServerHost(String.format("%s.%s", name, bootstrapHostDomain))
                            .withTls(tls)
                            .endEndpoint()
                        .withOauth(oauth)
                        .build())
                .build();
    }

    /**
     * Creates a dummy / test ManagedKafka with mostly invalid values in the default namespace
     */
    public static ManagedKafka getDummyInstance(int name) {
        return getDefault("mk-" + name, "mk-" + name, "xyz.com", CERT, CERT, "clientId", CERT, "secret",
                "claim", "fallbackClaim", "http://jwks", "https://token", "http://issuer", "strimzi-cluster-operator.v0.23.0", "2.7.0");
    }

    @JsonIgnore
    public boolean isReserveDeployment() {
        if (getMetadata().getLabels() == null) {
            return false;
        }
        return RESERVED_DEPLOYMENT_TYPE.equals(getMetadata().getLabels().get(DEPLOYMENT_TYPE));
    }

}
