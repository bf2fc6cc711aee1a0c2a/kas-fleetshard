package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuthBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaEndpointBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.test.mock.QuarkusKubeMockServer;
import org.bf2.test.mock.QuarkusKubernetesMockServer;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(QuarkusKubeMockServer.class)
@QuarkusTest
class KafkaClusterTest {

    @QuarkusKubernetesMockServer
    static KubernetesServer server;

    @Inject
    KafkaCluster kafkaCluster;

    @Test
    void testManagedKafkaToKafka() {
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
                                                .build()
                                )
                                .withNewVersions()
                                .withKafka("2.6.0")
                                .endVersions()
                                .build())
                .build();

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        var kafkaCli = server.getClient().customResources(Kafka.class, KafkaList.class);
        kafkaCli.create(kafka);
        assertNotNull(kafkaCli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get());
    }
}
