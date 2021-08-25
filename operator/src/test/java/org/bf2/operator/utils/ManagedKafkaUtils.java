package org.bf2.operator.utils;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuthBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaEndpointBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;

public class ManagedKafkaUtils {
    private ManagedKafkaUtils() {
    }

    public static ManagedKafka dummyManagedKafka(String id) {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.setId(id);
        return mk;
    }

    public static ManagedKafka exampleManagedKafka(String size) {
        ManagedKafka mk = new ManagedKafkaBuilder()
            .withMetadata(
                new ObjectMetaBuilder()
                    .withNamespace("test")
                    .withName("test-mk")
                    .build())
            .withSpec(
                new ManagedKafkaSpecBuilder()
                    .withEndpoint(
                        new ManagedKafkaEndpointBuilder()
                            .withBootstrapServerHost("xxx.yyy.zzz")
                            .build()
                    )
                    .withOauth(
                        new ManagedKafkaAuthenticationOAuthBuilder()
                            .withClientId("clientId")
                            .withClientSecret("clientSecret")
                            .withTokenEndpointURI("https://tokenEndpointURI")
                            .withJwksEndpointURI("https://jwksEndpointURI")
                            .withValidIssuerEndpointURI("https://validIssuerEndpointURI")
                            .withUserNameClaim("userNameClaim")
                            .withTlsTrustedCertificate("TLS trusted cert")
                            .build()
                    )
                    .withNewCapacity()
                    .withMaxDataRetentionSize(Quantity.parse(size))
                    .withIngressEgressThroughputPerSec(Quantity.parse("2Mi"))
                    .endCapacity()
                    .withNewVersions()
                        .withKafka("2.6.0")
                        .withStrimzi("0.23.0-2")
                    .endVersions()
                    .withOwners("userid-123")
                    .build())
            .build();
        return mk;
    }
}
