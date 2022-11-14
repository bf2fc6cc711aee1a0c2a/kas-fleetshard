package org.bf2.operator.managers;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.operator.ManagedKafkaKeys.Annotations;
import org.bf2.operator.clients.canary.CanaryStatusService;
import org.bf2.operator.clients.canary.Status;
import org.bf2.operator.clients.canary.Status.Consuming;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.time.Instant;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class KafkaManagerTest {

    @Inject
    ManagedKafkaResourceClient mkClient;

    @Inject
    KafkaManager kafkaManager;

    @InjectMock
    CanaryStatusService canaryStatus;

    @BeforeEach
    void setup() {
        mkClient.delete();
    }

    @Test
    void testKafkaUpgradeStabilityCheckStable() throws Exception {
        Status status = new Status();
        Consuming consumingStatus = new Consuming();
        consumingStatus.setPercentage(91);
        status.setConsuming(consumingStatus);
        Mockito.when(canaryStatus.get(Mockito.any())).thenReturn(status);

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().getAnnotations().put(Annotations.KAFKA_UPGRADE_START_TIMESTAMP, Instant.ofEpochMilli(1).toString());
        mk.getMetadata().getAnnotations().put(Annotations.KAFKA_UPGRADE_END_TIMESTAMP, Instant.ofEpochMilli(2).toString());

        String ns = mk.getMetadata().getNamespace();
        String name = mk.getMetadata().getName();

        mkClient.create(mk);

        kafkaManager.doKafkaUpgradeStabilityCheck(mk);

        mk = mkClient.getByName(ns, name);

        assertNull(mk.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_START_TIMESTAMP));
        assertNull(mk.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_END_TIMESTAMP));
    }

    @Test
    void testKafkaUpgradeStabilityCheckNotStable() throws Exception {
        Status status = new Status();
        Consuming consumingStatus = new Consuming();
        consumingStatus.setPercentage(90);
        status.setConsuming(consumingStatus);
        Mockito.when(canaryStatus.get(Mockito.any())).thenReturn(status);

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().getAnnotations().put(Annotations.KAFKA_UPGRADE_START_TIMESTAMP, Instant.ofEpochMilli(1).toString());
        mk.getMetadata().getAnnotations().put(Annotations.KAFKA_UPGRADE_END_TIMESTAMP, Instant.ofEpochMilli(2).toString());

        String ns = mk.getMetadata().getNamespace();
        String name = mk.getMetadata().getName();

        mkClient.create(mk);

        kafkaManager.doKafkaUpgradeStabilityCheck(mk);

        mk = mkClient.getByName(ns, name);

        assertNotNull(mk.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_START_TIMESTAMP));
        assertNull(mk.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_END_TIMESTAMP));
    }

    @Test
    void testKafkaUpgradeStabilityCheckStatusFetchFailure() throws Exception {
        Mockito.when(canaryStatus.get(Mockito.any())).thenThrow(RuntimeException.class);

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().getAnnotations().put(Annotations.KAFKA_UPGRADE_START_TIMESTAMP, Instant.ofEpochMilli(1).toString());
        mk.getMetadata().getAnnotations().put(Annotations.KAFKA_UPGRADE_END_TIMESTAMP, Instant.ofEpochMilli(2).toString());

        String ns = mk.getMetadata().getNamespace();
        String name = mk.getMetadata().getName();

        mkClient.create(mk);

        kafkaManager.doKafkaUpgradeStabilityCheck(mk);

        mk = mkClient.getByName(ns, name);

        assertNotNull(mk.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_START_TIMESTAMP));
        assertNull(mk.getMetadata().getAnnotations().get(Annotations.KAFKA_UPGRADE_END_TIMESTAMP));
    }
}
