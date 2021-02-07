package org.bf2.operator.operands;

import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.Versions;
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
        //TODO rewrite to builder
        ManagedKafka mk = new ManagedKafka();
        mk.getMetadata().setName("test-mk");
        mk.getMetadata().setNamespace("test");
        mk.setSpec(new ManagedKafkaSpec());
        Versions v = new Versions();
        v.setKafka("2.6.0");
        mk.getSpec().setVersions(v);

        Kafka kafka = kafkaCluster.kafkaFrom(mk);

        var kafkaCli = server.getClient().customResources(Kafka.class, KafkaList.class);
        kafkaCli.create(kafka);
        assertNotNull(kafkaCli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get());
    }
}
