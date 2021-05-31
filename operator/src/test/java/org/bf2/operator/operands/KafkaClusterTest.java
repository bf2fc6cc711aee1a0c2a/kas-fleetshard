package org.bf2.operator.operands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
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
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuthBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaEndpointBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class KafkaClusterTest {

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    KafkaCluster kafkaCluster;

    @Test
    void testManagedKafkaToKafka() throws IOException {
        ManagedKafka mk = exampleManagedKafka("60Gi");

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        JsonNode patch = diffToExpected(kafka);
        assertEquals("[]", patch.toString());

        var kafkaCli = server.getClient().customResources(Kafka.class, KafkaList.class);
        kafkaCli.create(kafka);
        assertNotNull(kafkaCli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get());
    }

    private ManagedKafka exampleManagedKafka(String size) {
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
                                .withMaxDataRetentionSize(Quantity.parse(size))
                                .withIngressEgressThroughputPerSec(Quantity.parse("2Mi"))
                                .endCapacity()
                                .withNewVersions()
                                    .withKafka("2.6.0")
                                    .withStrimzi("0.22.1")
                                .endVersions()
                                .build())
                .build();
        return mk;
    }

    @Test
    void testManagedKafkaToKafkaWithSizeChanges() throws IOException {
        ManagedKafka mk = exampleManagedKafka("60Gi");

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        Kafka reduced = kafkaCluster.kafkaFrom(exampleManagedKafka("40Gi"), kafka);

        // should not change to a smaller size
        JsonNode patch = diffToExpected(reduced);
        assertEquals("[]", patch.toString());

        Kafka larger = kafkaCluster.kafkaFrom(exampleManagedKafka("80Gi"), kafka);

        // should change to a larger size
        patch = diffToExpected(larger);
        assertEquals("[{\"op\":\"replace\",\"path\":\"/spec/kafka/config/client.quota.callback.static.storage.soft\",\"value\":\"35433480191\"},{\"op\":\"replace\",\"path\":\"/spec/kafka/config/client.quota.callback.static.storage.hard\",\"value\":\"37402006868\"},{\"op\":\"replace\",\"path\":\"/spec/kafka/storage/volumes/0/size\",\"value\":\"39370533546\"}]", patch.toString());
    }

    private JsonNode diffToExpected(Kafka kafka) throws IOException, JsonProcessingException, JsonMappingException {
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        JsonNode file1 = objectMapper.readTree(KafkaClusterTest.class.getResourceAsStream("/expected/strimzi.yml"));
        JsonNode file2 = objectMapper.readTree(Serialization.asYaml(kafka));
        JsonNode patch = JsonDiff.asJson(file1, file2);
        return patch;
    }
}
