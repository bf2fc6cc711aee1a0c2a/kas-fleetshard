package org.bf2.operator.managers;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.operator.ManagedKafkaKeys.Annotations;
import org.bf2.operator.clients.canary.CanaryStatusService;
import org.bf2.operator.clients.canary.Status;
import org.bf2.operator.clients.canary.Status.Consuming;
import org.bf2.operator.operands.KafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.time.Instant;
import java.util.function.Consumer;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class KafkaManagerTest {

    @Inject
    ManagedKafkaResourceClient mkClient;

    @Inject
    KubernetesClient client;

    @Inject
    InformerManager informerManager;

    @Inject
    KafkaManager kafkaManager;

    @InjectMock
    CanaryStatusService canaryStatus;

    @Inject
    KafkaCluster kafkaCluster;

    @BeforeEach
    void setup() {
        informerManager.createKafkaInformer();
        client.resources(Kafka.class).inAnyNamespace().delete();
        client.resources(ManagedKafka.class).inAnyNamespace().delete();
    }

    @Test
    void testKafkaUpgradeStabilityCheckStable() throws Exception {
        Status status = new Status();
        Consuming consumingStatus = new Consuming();
        consumingStatus.setPercentage(91);
        status.setConsuming(consumingStatus);
        Mockito.when(canaryStatus.get(Mockito.any())).thenReturn(status);

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        Kafka kafka = upgradeStabilityCheck(mk, b -> {});

        assertNull(kafka.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_START_TIMESTAMP));
        assertNull(kafka.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_END_TIMESTAMP));
    }

    @Test
    void testKafkaUpgradeStabilityCheckNotStable() throws Exception {
        Status status = new Status();
        Consuming consumingStatus = new Consuming();
        consumingStatus.setPercentage(90);
        status.setConsuming(consumingStatus);
        Mockito.when(canaryStatus.get(Mockito.any())).thenReturn(status);

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);

        Kafka kafka = upgradeStabilityCheck(mk, b -> {});

        assertNotNull(kafka.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_START_TIMESTAMP));
        assertNull(kafka.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_END_TIMESTAMP));
    }

    private Kafka upgradeStabilityCheck(ManagedKafka mk, Consumer<KafkaBuilder> consumer) {
        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        KafkaBuilder builder = new KafkaBuilder(kafka);
        builder.editMetadata()
                .addToAnnotations(Annotations.KAFKA_UPGRADE_START_TIMESTAMP, Instant.ofEpochMilli(1).toString())
                .addToAnnotations(Annotations.KAFKA_UPGRADE_END_TIMESTAMP, Instant.ofEpochMilli(2).toString())
                .endMetadata();
        consumer.accept(builder);
        kafka = builder.build();

        client.resource(kafka).createOrReplace();

        mkClient.create(mk);

        kafkaManager.doKafkaUpgradeStabilityCheck(mk);

        return client.resource(kafka).fromServer().get();
    }

    @Test
    void testKafkaUpgradeStabilityCheckStatusFetchFailure() throws Exception {
        Mockito.when(canaryStatus.get(Mockito.any())).thenThrow(RuntimeException.class);

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        Kafka kafka = upgradeStabilityCheck(mk, b -> {});

        assertNotNull(kafka.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_START_TIMESTAMP));
        assertNull(kafka.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_END_TIMESTAMP));
    }
}
