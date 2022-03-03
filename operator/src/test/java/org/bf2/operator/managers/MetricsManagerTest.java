package org.bf2.operator.managers;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.search.Search;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import org.bf2.common.OperandUtils;
import org.bf2.operator.operands.KafkaCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_BROKERS_DESIRED_COUNT;
import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_CONNECTION_CREATION_RATE_LIMIT;
import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_CONNECTION_LIMIT;
import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_MAX_MESSAGE_SIZE_LIMIT;
import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_PARTITION_LIMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class MetricsManagerTest {
    @Inject
    KubernetesClient client;

    @Inject
    @Dependent
    MetricsManager metricsManager;

    @Inject
    MeterRegistry meterRegistry;

    private MixedOperation<Kafka, KafkaList, Resource<Kafka>> kafkaClient;

    @BeforeEach
    public void setup() {
        meterRegistry.clear();
        kafkaClient = client.resources(Kafka.class, KafkaList.class);
        kafkaClient.inAnyNamespace().delete();
        kafkaClient.list().getItems().forEach(kafkaClient::delete);
        metricsManager.createInformer();
    }

    @AfterEach
    public void teardown() {
        meterRegistry.clear();
        kafkaClient.inAnyNamespace().delete();
    }

    @Test
    public void instanceMetrics(TestInfo info) {
        int expectedReplicas = 3;
        int expectedPartitionLimit = 1000;
        int expectedMaxMessageSizeLimit = 2048;
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                .withNamespace(client.getNamespace())
                .withName(info.getTestMethod().get().getName())
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withNewSpec()
                .withNewKafka()
                .withReplicas(expectedReplicas)
                .withConfig(Map.of(KafkaCluster.MAX_PARTITIONS, expectedPartitionLimit,
                                   KafkaCluster.MESSAGE_MAX_BYTES, expectedMaxMessageSizeLimit))
                .endKafka()
                .endSpec()
                .build();

        int expectedNumberOfMeters = 3;
        kafkaClient.createOrReplace(kafka);
        awaitMetersMatchingTags(Tags.of(MetricsManager.OWNER), expectedNumberOfMeters, "unexpected number of meters overall");

        assertEquals(expectedNumberOfMeters, Search.in(meterRegistry).tags(List.of(MetricsManager.OWNER)).meters().size(), "unexpected number of meters overall");

        Tags namespaceNameTags = Tags.of(Tag.of("namespace", client.getNamespace()), Tag.of("name", kafka.getMetadata().getName()));
        Collection<Meter> metersByNamespaceName = Search.in(meterRegistry).tags(namespaceNameTags).meters();
        assertEquals(Search.in(meterRegistry).tags(List.of(MetricsManager.OWNER)).meters().size(), metersByNamespaceName.size(), "unexpected number of meters registered for this namespace/name");

        assertMeter(expectedReplicas, namespaceNameTags, KAFKA_INSTANCE_BROKERS_DESIRED_COUNT);
        assertMeter(expectedPartitionLimit, namespaceNameTags, KAFKA_INSTANCE_PARTITION_LIMIT);
        assertMeter(expectedMaxMessageSizeLimit, namespaceNameTags, KAFKA_INSTANCE_MAX_MESSAGE_SIZE_LIMIT);
    }
    @Test
    public void brokerMetrics(TestInfo info) throws Exception {
        int expectedReplicas = 1;
        int expectedConnections = 1000;
        int expectedConnectionCreationLimit = 100;
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                .withNamespace(client.getNamespace())
                .withName(info.getTestMethod().get().getName())
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withNewSpec()
                .withNewKafka()
                .withReplicas(expectedReplicas)
                .withListeners(new GenericKafkaListenerBuilder()
                        .withName("external")
                        .withNewConfiguration()
                        .withMaxConnections(expectedConnections)
                        .withMaxConnectionCreationRate(expectedConnectionCreationLimit)
                        .endConfiguration()
                        .build())
                .endKafka()
                .endSpec()
                .build();

        int expectedNumberOfMeters = 5;
        kafkaClient.createOrReplace(kafka);
        awaitMetersMatchingTags(Tags.of(MetricsManager.OWNER), expectedNumberOfMeters, "unexpected number of meters overall");

        Tags namespaceNameTags = Tags.of(Tag.of("namespace", client.getNamespace()), Tag.of("name", kafka.getMetadata().getName()));
        Tags brokerTags = Tags.concat(namespaceNameTags, Tags.of(Tag.of("broker", "0")));
        Tags brokerListenerTags = Tags.concat(brokerTags, Tags.of(Tag.of("listener", "external")));

        int expectedNumberOfBrokerMeters = 2;
        assertEquals(expectedNumberOfBrokerMeters, Search.in(meterRegistry).tags(brokerTags).meters().size(), "unexpected number of broker meters overall");

        int expectedNumberOfListenerMeters = 2;
        assertEquals(expectedNumberOfListenerMeters, Search.in(meterRegistry).tags(brokerListenerTags).meters().size(), "unexpected number of broker listener meters overall");

        assertMeter(expectedConnections, brokerListenerTags, KAFKA_INSTANCE_CONNECTION_LIMIT);
        assertMeter(expectedConnectionCreationLimit, brokerListenerTags, KAFKA_INSTANCE_CONNECTION_CREATION_RATE_LIMIT);
    }

    @Test
    public void deletingKafkaInstanceDeletesMetersToo(TestInfo info) throws Exception {
        Kafka kafka1 = new KafkaBuilder()
                .withNewMetadata()
                .withNamespace(client.getNamespace())
                .withName(info.getTestMethod().get().getName() + "1")
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withNewSpec()
                .withNewKafka()
                .withReplicas(3)
                .endKafka()
                .endSpec()
                .build();
        Kafka kafka2 = new KafkaBuilder()
                .withNewMetadata()
                .withNamespace(client.getNamespace())
                .withName(info.getTestMethod().get().getName() + "2")
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withNewSpec()
                .withNewKafka()
                .withReplicas(3)
                .endKafka()
                .endSpec()
                .build();

        Tags kafka1tags = Tags.of(Tag.of("namespace", kafka1.getMetadata().getNamespace()), Tag.of("name", kafka1.getMetadata().getName()));
        Tags kafka2tags = Tags.of(Tag.of("namespace", kafka2.getMetadata().getNamespace()), Tag.of("name", kafka2.getMetadata().getName()));

        int metersPerKafka = 3;
        kafkaClient.createOrReplace(kafka1);
        kafkaClient.createOrReplace(kafka2);
        awaitMetersMatchingTags(kafka1tags, metersPerKafka, "unexpected number of meters for kafka 1");
        awaitMetersMatchingTags(kafka2tags, metersPerKafka, "unexpected number of meters for kafka 2");

        kafkaClient.delete(kafka1);

        awaitMetersMatchingTags(kafka1tags, 0, "unexpected number of meters for kafka 1 after its deletion");
        awaitMetersMatchingTags(kafka2tags, metersPerKafka, "unexpected number of meters for kafka 2");
    }

    private void awaitMetersMatchingTags(Tags tags, int expectedMeters, String message) {
        await().atMost(Duration.ofMillis(250)).untilAsserted(() -> {
            assertEquals(expectedMeters, Search.in(meterRegistry).tags(tags).meters().size(), message);
        });
    }

    private void assertMeter(int expectedReplicas, Iterable<Tag> tags, String meterName) {
        Meter meter = Search.in(meterRegistry).tags(tags).name(meterName).meter();
        assertNotNull(meter, String.format("meter named %s with tags %s not found", meterName, tags));
        assertEquals(expectedReplicas, meter.measure().iterator().next().getValue());
    }

}