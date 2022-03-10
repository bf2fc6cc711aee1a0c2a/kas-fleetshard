package org.bf2.operator.managers;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.search.Search;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import org.bf2.common.OperandUtils;
import org.bf2.operator.operands.KafkaCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import javax.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_CONNECTION_CREATION_RATE_LIMIT;
import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_CONNECTION_LIMIT;
import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_MAX_MESSAGE_SIZE_LIMIT;
import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_PARTITION_LIMIT;
import static org.bf2.operator.managers.MetricsManager.KAFKA_INSTANCE_SPEC_BROKERS_DESIRED_COUNT;
import static org.bf2.operator.managers.MetricsManager.TAG_LABEL_BROKER_ID;
import static org.bf2.operator.managers.MetricsManager.TAG_LABEL_INSTANCE_NAME;
import static org.bf2.operator.managers.MetricsManager.TAG_LABEL_LISTENER;
import static org.bf2.operator.managers.MetricsManager.TAG_LABEL_NAMESPACE;
import static org.bf2.operator.operands.AbstractKafkaCluster.EXTERNAL_LISTENER_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class MetricsManagerTest {
    private static final String NAMESPACE = "namespace";

    @Inject
    MetricsManager metricsManager;

    @Inject
    MeterRegistry meterRegistry;

    @BeforeEach
    @AfterEach
    public void clean() {
        meterRegistry.clear();
    }

    @Test
    public void instanceMetrics(TestInfo info) {
        int expectedReplicas = 3;
        int expectedPartitionLimit = 1000;
        int expectedMaxMessageSizeLimit = 2048;
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                .withNamespace(NAMESPACE)
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
        metricsManager.onAdd(kafka);
        awaitMetersMatchingTags(Tags.of(MetricsManager.OWNER), expectedNumberOfMeters, "unexpected number of meters overall");

        assertEquals(expectedNumberOfMeters, Search.in(meterRegistry).tags(List.of(MetricsManager.OWNER)).meters().size(), "unexpected number of meters overall");

        Tags namespaceNameTags = Tags.of(Tag.of(TAG_LABEL_NAMESPACE, NAMESPACE), Tag.of(TAG_LABEL_INSTANCE_NAME, kafka.getMetadata().getName()));
        Collection<Meter> metersByNamespaceName = Search.in(meterRegistry).tags(namespaceNameTags).meters();
        assertEquals(Search.in(meterRegistry).tags(List.of(MetricsManager.OWNER)).meters().size(), metersByNamespaceName.size(), "unexpected number of meters registered for this namespace/name");

        assertMeter(expectedReplicas, namespaceNameTags, KAFKA_INSTANCE_SPEC_BROKERS_DESIRED_COUNT);
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
                .withNamespace(NAMESPACE)
                .withName(info.getTestMethod().get().getName())
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withNewSpec()
                .withNewKafka()
                .withReplicas(expectedReplicas)
                .withListeners(new GenericKafkaListenerBuilder()
                        .withName(EXTERNAL_LISTENER_NAME)
                        .withPort(8080)
                        .withNewConfiguration()
                        .withMaxConnections(expectedConnections)
                        .withMaxConnectionCreationRate(expectedConnectionCreationLimit)
                        .endConfiguration()
                        .build())
                .endKafka()
                .endSpec()
                .build();

        int expectedNumberOfMeters = 5;
        metricsManager.onAdd(kafka);
        awaitMetersMatchingTags(Tags.of(MetricsManager.OWNER), expectedNumberOfMeters, "unexpected number of meters overall");

        Tags namespaceNameTags = Tags.of(Tag.of(TAG_LABEL_NAMESPACE, NAMESPACE), Tag.of(TAG_LABEL_INSTANCE_NAME, kafka.getMetadata().getName()));
        Tags brokerTags = Tags.concat(namespaceNameTags, Tags.of(Tag.of(TAG_LABEL_BROKER_ID, "0")));
        Tags brokerListenerTags = Tags.concat(brokerTags, Tags.of(Tag.of(TAG_LABEL_LISTENER, "EXTERNAL-8080")));

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
                .withNamespace(NAMESPACE)
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
                .withNamespace(NAMESPACE)
                .withName(info.getTestMethod().get().getName() + "2")
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withNewSpec()
                .withNewKafka()
                .withReplicas(3)
                .endKafka()
                .endSpec()
                .build();

        Tags kafka1tags = Tags.of(Tag.of(TAG_LABEL_NAMESPACE, kafka1.getMetadata().getNamespace()), Tag.of(TAG_LABEL_INSTANCE_NAME, kafka1.getMetadata().getName()));
        Tags kafka2tags = Tags.of(Tag.of(TAG_LABEL_NAMESPACE, kafka2.getMetadata().getNamespace()), Tag.of(TAG_LABEL_INSTANCE_NAME, kafka2.getMetadata().getName()));

        int metersPerKafka = 3;
        metricsManager.onAdd(kafka1);
        metricsManager.onAdd(kafka2);
        awaitMetersMatchingTags(kafka1tags, metersPerKafka, "unexpected number of meters for kafka 1");
        awaitMetersMatchingTags(kafka2tags, metersPerKafka, "unexpected number of meters for kafka 2");

        metricsManager.onDelete(kafka1, false);

        awaitMetersMatchingTags(kafka1tags, 0, "unexpected number of meters for kafka 1 after its deletion");
        awaitMetersMatchingTags(kafka2tags, metersPerKafka, "unexpected number of meters for kafka 2");
    }

    @Test
    public void metricReflectsUpdates(TestInfo info) {
        Kafka nopSpec = new KafkaBuilder()
                .withNewMetadata()
                .withNamespace(NAMESPACE)
                .withName(info.getTestMethod().get().getName())
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .build();

        metricsManager.onAdd(nopSpec);

        Tags namespaceNameTags = Tags.of(Tag.of(TAG_LABEL_NAMESPACE, NAMESPACE), Tag.of(TAG_LABEL_INSTANCE_NAME, nopSpec.getMetadata().getName()));
        assertMeter(Double.NaN, namespaceNameTags, KAFKA_INSTANCE_SPEC_BROKERS_DESIRED_COUNT);

        int expectedReplicas = 3;
        Kafka update = new KafkaBuilder(nopSpec)
                .withNewSpec()
                .withNewKafka()
                .withReplicas(expectedReplicas)
                .endKafka()
                .endSpec()
                .build();

        metricsManager.onUpdate(nopSpec, update);
        assertMeter(expectedReplicas, namespaceNameTags, KAFKA_INSTANCE_SPEC_BROKERS_DESIRED_COUNT);

        int expectedMaxConnections = 100;
        Kafka updateAgain = new KafkaBuilder(update)
                .withNewSpec()
                .withNewKafka()
                .withReplicas(expectedReplicas)
                .withListeners(new GenericKafkaListenerBuilder()
                        .withName(EXTERNAL_LISTENER_NAME)
                        .withPort(8080)
                        .withNewConfiguration()
                        .withMaxConnections(expectedMaxConnections)
                        .endConfiguration()
                        .build())
                .endKafka()
                .endSpec()
                .build();

        metricsManager.onUpdate(update, updateAgain);

        int expectedNumberOfListenerMeters = expectedReplicas * 2;

        Tags listenerTags = Tags.of(Tag.of(TAG_LABEL_LISTENER, "EXTERNAL-8080"));
        assertEquals(expectedNumberOfListenerMeters, Search.in(meterRegistry).tags(listenerTags).meters().size(), "unexpected number of broker listener meters overall");

        Tags brokerTags = Tags.concat(namespaceNameTags, Tags.of(Tag.of(TAG_LABEL_BROKER_ID, "0")));
        Tags brokerListenerTags = Tags.concat(brokerTags, listenerTags);

        assertMeter(expectedMaxConnections, brokerListenerTags, KAFKA_INSTANCE_CONNECTION_LIMIT);

        // Reduce the replicas and change the connection limit.
        expectedReplicas = 1;
        expectedMaxConnections = 101;
        Kafka reduceReplicas = new KafkaBuilder(update)
                .withNewSpec()
                .withNewKafka()
                .withReplicas(expectedReplicas)
                .withListeners(new GenericKafkaListenerBuilder()
                        .withName(EXTERNAL_LISTENER_NAME)
                        .withPort(8080)
                        .withNewConfiguration()
                        .withMaxConnections(expectedMaxConnections)
                        .endConfiguration()
                        .build())
                .endKafka()

                .endSpec()
                .build();

        metricsManager.onUpdate(update, reduceReplicas);

        expectedNumberOfListenerMeters = expectedReplicas * 2;
        assertEquals(expectedNumberOfListenerMeters, Search.in(meterRegistry).tags(listenerTags).meters().size(), "unexpected number of broker listener meters overall after reduction in replica");

        assertMeter(expectedMaxConnections, brokerListenerTags, KAFKA_INSTANCE_CONNECTION_LIMIT);

    }


    private void awaitMetersMatchingTags(Tags tags, int expectedMeters, String message) {
        assertEquals(expectedMeters, Search.in(meterRegistry).tags(tags).meters().size(), message);
    }

    private void assertMeter(double expectedValue, Iterable<Tag> tags, String meterName) {
        Meter meter = Search.in(meterRegistry).tags(tags).name(meterName).meter();
        assertNotNull(meter, String.format("meter named %s with tags %s not found", meterName, tags));
        assertEquals(expectedValue, meter.measure().iterator().next().getValue());
    }

}
