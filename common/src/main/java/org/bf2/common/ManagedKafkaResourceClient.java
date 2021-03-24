package org.bf2.common;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;

/**
 * Represents a wrapper around a Kubernetes client for handling operations on a ManagedKafka custom resource
 */
@ApplicationScoped
public class ManagedKafkaResourceClient extends AbstractCustomResourceClient<ManagedKafka, ManagedKafkaList> {

    private static final String CERT = "cert";

    @Override
    protected Class<ManagedKafka> getCustomResourceClass() {
        return ManagedKafka.class;
    }

    @Override
    protected Class<ManagedKafkaList> getCustomResourceListClass() {
        return ManagedKafkaList.class;
    }

    /**
     * Effectively a template for creating default {@link ManagedKafka} instances.
     */
    public static ManagedKafka getDefault(String name, String namespace, String bootstrapHostDomain,
            String endpointTlsCert, String endpointTlsKey, String oauthClientId, String oauthTlsCert,
            String oauthClientSecret, String oauthUserClaim, String oauthJwksEndpoint, String oauthTokenEndpoint,
            String oauthIssuerEndpoint) {
        ManagedKafka mk = new ManagedKafkaBuilder()
                .withMetadata(new ObjectMetaBuilder().withNamespace(namespace).withName(name).build())
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
        return getDefault(String.valueOf(name), null, "xyz.com", CERT, CERT, "clientId", CERT, "secret",
                "claim", "http://jwks", "https://token", "http://issuer");
    }
}
