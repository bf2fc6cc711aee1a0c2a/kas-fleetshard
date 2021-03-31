package org.bf2.operator.resources.v1alpha1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.dekorate.crd.annotation.Crd;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.BuildableReference;

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
@Crd(group = "managedkafka.bf2.org", version = "v1alpha1")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ManagedKafka extends CustomResource<ManagedKafkaSpec, ManagedKafkaStatus> implements Namespaced {

    private static final String CERT = "cert";

    public final static String BF2_DOMAIN = "bf2.org/";
    public final static String ID = BF2_DOMAIN + "id";
    public final static String PLACEMENT_ID = BF2_DOMAIN + "placementId";

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
     * Effectively a template for creating default {@link ManagedKafka} instances.
     */
    public static ManagedKafka getDefault(String name, String namespace, String bootstrapHostDomain,
            String endpointTlsCert, String endpointTlsKey, String oauthClientId, String oauthTlsCert,
            String oauthClientSecret, String oauthUserClaim, String oauthJwksEndpoint, String oauthTokenEndpoint,
            String oauthIssuerEndpoint) {
        ManagedKafka mk = new ManagedKafkaBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withNamespace(namespace)
                        .withName(name)
                        .addToAnnotations(ID, name)
                        .addToAnnotations(PLACEMENT_ID, name)
                        .build())
                .withSpec(new ManagedKafkaSpecBuilder().withNewVersions()
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
                        .withNewBootstrapServerHost(String.format("%s.%s", name, bootstrapHostDomain))
                        .withNewTls()
                        .withNewCert(endpointTlsCert)
                        .withNewKey(endpointTlsKey)
                        .endTls()
                        .endEndpoint()
                        .withNewOauth()
                        .withClientId(oauthClientId)
                        .withNewTlsTrustedCertificate(oauthTlsCert)
                        .withClientSecret(oauthClientSecret)
                        .withUserNameClaim(oauthUserClaim)
                        .withNewJwksEndpointURI(oauthJwksEndpoint)
                        .withNewTokenEndpointURI(oauthTokenEndpoint)
                        .withNewValidIssuerEndpointURI(oauthIssuerEndpoint)
                        .endOauth()
                        .build())
                .build();
        mk.setPlacementId(UUID.randomUUID().toString());
        mk.setId(name);
        return mk;
    }

    /**
     * Creates a dummy / test ManagedKafka with mostly invalid values in the default namespace
     */
    public static ManagedKafka getDummyInstance(int name) {
        return getDefault("mk-" + name, "mk-" + name, "xyz.com", CERT, CERT, "clientId", CERT, "secret",
                "claim", "http://jwks", "https://token", "http://issuer");
    }

}
