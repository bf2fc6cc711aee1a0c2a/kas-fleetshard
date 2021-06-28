package org.bf2.operator.operands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.zjsonpatch.JsonDiff;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.DrainCleanerManager;
import org.bf2.operator.StorageClassManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.io.IOException;
import java.util.Map;

import static org.bf2.operator.utils.ManagedKafkaUtils.exampleManagedKafka;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class KafkaClusterTest {

    @Inject
    KubernetesClient client;

    @Inject
    KafkaCluster kafkaCluster;

    @Inject
    StorageClassManager storageClassManager;

    @BeforeEach
    void createDefaultStorageClass() {
        StorageClass defaultStorageClass = new StorageClassBuilder()
                .editOrNewMetadata()
                    .withName("default-storageclass")
                    .withAnnotations(Map.of("storageclass.kubernetes.io/is-default-class", "true"))
                .endMetadata()
                .withProvisioner("kubernetes.io/aws-ebs")
                .withReclaimPolicy("Delete")
                .withVolumeBindingMode("WaitForFirstConsumer")
                .withAllowVolumeExpansion(true)
                .build();

        client.storage().storageClasses().createOrReplace(defaultStorageClass);
        storageClassManager.reconcileStorageClasses();
    }

    @Test
    void testManagedKafkaToKafka() throws IOException {
        ManagedKafka mk = exampleManagedKafka("60Gi");

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        JsonNode patch = diffToExpected(kafka);
        assertEquals("[]", patch.toString());

        var kafkaCli = client.customResources(Kafka.class, KafkaList.class);
        kafkaCli.create(kafka);
        assertNotNull(kafkaCli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).get());
    }

    @Test
    void testManagedKafkaToKafkaWithSizeChanges() throws IOException {
        Kafka kafka = kafkaCluster.kafkaFrom(exampleManagedKafka("60Gi"), null);

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

    @Test
    void testDrainCleanerWebhookFound() throws IOException {
        DrainCleanerManager mock = Mockito.mock(DrainCleanerManager.class);
        Mockito.when(mock.isDrainCleanerWebhookFound()).thenReturn(true);
        QuarkusMock.installMockForType(mock, DrainCleanerManager.class);

        ManagedKafka mk = exampleManagedKafka("40Gi");
        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        assertEquals(0, kafka.getSpec().getKafka().getTemplate().getPodDisruptionBudget().getMaxUnavailable());
        assertEquals(0, kafka.getSpec().getZookeeper().getTemplate().getPodDisruptionBudget().getMaxUnavailable());
    }

    @Test
    void testDrainCleanerWebhookNotFound() throws IOException {
        DrainCleanerManager mock = Mockito.mock(DrainCleanerManager.class);
        Mockito.when(mock.isDrainCleanerWebhookFound()).thenReturn(false);
        QuarkusMock.installMockForType(mock, DrainCleanerManager.class);

        ManagedKafka mk = exampleManagedKafka("40Gi");
        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        assertNull(kafka.getSpec().getKafka().getTemplate().getPodDisruptionBudget());
        assertNull(kafka.getSpec().getZookeeper().getTemplate().getPodDisruptionBudget());
    }
}
