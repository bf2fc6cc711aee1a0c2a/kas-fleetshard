package org.bf2.operator;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.Versions;
import org.bf2.test.mock.KubeMockServer;
import org.bf2.test.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


@QuarkusTestResource(KubeMockServer.class)
@QuarkusTest
public class AgentOperatorTest {

    @KubernetesMockServer
    KubernetesServer server;

    @Test
    void testConnectOperatorToCluster() throws InterruptedException {
        var managedKafkas = server.getClient().customResources(ManagedKafka.class);
        server.getClient().namespaces().create(new NamespaceBuilder().withNewMetadata().withName("test").endMetadata().build());
        managedKafkas.inNamespace("test").create(exampleManagedKafka());

        Thread.sleep(20_000);

        var kafkaCli = server.getClient().customResources(Kafka.class, KafkaList.class);
        assertEquals(1, kafkaCli.inNamespace("test").list().getItems().size());
    }

    private ManagedKafka exampleManagedKafka() {
        ManagedKafka managedKafka = new ManagedKafka();
        managedKafka.getMetadata().setNamespace("test");
        managedKafka.getMetadata().setName("name");
        ManagedKafkaSpec spec = new ManagedKafkaSpec();
        Versions versions = new Versions();
        versions.setKafka("2.7");
        spec.setVersions(versions);
        managedKafka.setSpec(spec);
        return managedKafka;
    }
}
