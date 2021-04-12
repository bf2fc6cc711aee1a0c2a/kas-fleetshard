package org.bf2.operator.operands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.zjsonpatch.JsonDiff;
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

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(QuarkusKubeMockServer.class)
@QuarkusTest
class KafkaClusterTest {

    @QuarkusKubernetesMockServer
    static KubernetesServer server;

    @Inject
    KafkaCluster kafkaCluster;

    @Test
    void testManagedKafkaToKafka() throws IOException {
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
                                .withNewCapacity()
                                .withMaxDataRetentionSize(Quantity.parse("60Gi"))
                                .withIngressEgressThroughputPerSec(Quantity.parse("2Mi"))
                                .endCapacity()
                                .withNewVersions()
                                .withKafka("2.6.0")
                                .endVersions()
                                .build())
                .build();

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        JsonNode file1 = objectMapper.readTree(KafkaClusterTest.class.getResourceAsStream("/expected/strimzi.yml"));
        JsonNode file2 = objectMapper.readTree(Serialization.asYaml(kafka));
        JsonNode patch = JsonDiff.asJson(file1, file2);
        assertEquals("[]", patch.toString());

        var kafkaCli = server.getClient().customResources(Kafka.class, KafkaList.class);
        kafkaCli.create(kafka);
        assertNotNull(kafkaCli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get());
    }
}
