package org.bf2.operator.operands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.zjsonpatch.JsonDiff;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.api.kafka.model.storage.JbodStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverride;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverrideBuilder;
import org.bf2.operator.managers.DrainCleanerManager;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.managers.IngressControllerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.bf2.operator.utils.ManagedKafkaUtils.exampleManagedKafka;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class KafkaClusterTest {

    @Inject
    KubernetesClient client;

    @Inject
    KafkaCluster kafkaCluster;

    @Inject
    InformerManager informerManager;

    @Inject
    IngressControllerManager ingressControllerManager;

    @BeforeEach
    void beforeEach() {
        informerManager.createKafkaInformer();

        // Make label set more stable
        ingressControllerManager.addToRouteMatchLabels("managedkafka.bf2.org/kas-multi-zone", "true");
        ingressControllerManager.addToRouteMatchLabels("managedkafka.bf2.org/kas-zone0", "true");
        ingressControllerManager.addToRouteMatchLabels("managedkafka.bf2.org/kas-zone1", "true");
        ingressControllerManager.addToRouteMatchLabels("managedkafka.bf2.org/kas-zone2", "true");
    }

    @Test
    void testManagedKafkaToKafka() throws IOException {
        KafkaInstanceConfiguration config = kafkaCluster.getKafkaConfiguration();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            KafkaInstanceConfiguration clone = objectMapper.readValue(objectMapper.writeValueAsString(config), KafkaInstanceConfiguration.class);
            clone.getKafka().setOneInstancePerNode(false);
            clone.getKafka().setColocateWithZookeeper(false);
            clone.getExporter().setColocateWithZookeeper(false);

            kafkaCluster.setKafkaConfiguration(clone);
            ManagedKafka mk = exampleManagedKafka("60Gi");

            Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

            diffToExpected(kafka, "/expected/strimzi.yml");
        } finally {
            kafkaCluster.setKafkaConfiguration(config);
        }
    }

    @Test
    void testManagedKafkaToKafkaWithSizeChanges() throws IOException {
        KafkaInstanceConfiguration config = kafkaCluster.getKafkaConfiguration();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            KafkaInstanceConfiguration clone = objectMapper.readValue(objectMapper.writeValueAsString(config), KafkaInstanceConfiguration.class);
            clone.getKafka().setOneInstancePerNode(false);
            clone.getKafka().setColocateWithZookeeper(false);
            clone.getExporter().setColocateWithZookeeper(false);

            kafkaCluster.setKafkaConfiguration(clone);
            Kafka kafka = kafkaCluster.kafkaFrom(exampleManagedKafka("60Gi"), null);

            Kafka reduced = kafkaCluster.kafkaFrom(exampleManagedKafka("40Gi"), kafka);

            // should not change to a smaller size
            diffToExpected(reduced, "/expected/strimzi.yml");

            Kafka larger = kafkaCluster.kafkaFrom(exampleManagedKafka("80Gi"), kafka);

            // should change to a larger size
            diffToExpected(larger, "/expected/strimzi.yml", "[{\"op\":\"replace\",\"path\":\"/spec/kafka/config/client.quota.callback.static.storage.soft\",\"value\":\"35433480191\"},{\"op\":\"replace\",\"path\":\"/spec/kafka/config/client.quota.callback.static.storage.hard\",\"value\":\"37402006868\"},{\"op\":\"replace\",\"path\":\"/spec/kafka/storage/volumes/0/size\",\"value\":\"39370533546\"}]");
        } finally {
            kafkaCluster.setKafkaConfiguration(config);
        }
    }

    @Test
    void testManagedKafkaToKafkaWithCustomConfiguration() throws IOException {
        KafkaInstanceConfiguration config = kafkaCluster.getKafkaConfiguration();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            KafkaInstanceConfiguration clone = objectMapper.readValue(objectMapper.writeValueAsString(config), KafkaInstanceConfiguration.class);

            clone.getKafka().setConnectionAttemptsPerSec(300);
            clone.getKafka().setReplicas(4);
            clone.getKafka().setContainerMemory("2Gi");
            clone.getKafka().setJvmXx("foo bar, foo2 bar2");

            clone.getZookeeper().setReplicas(5);
            clone.getZookeeper().setContainerMemory("11Gi");
            clone.getZookeeper().setJvmXx("zkfoo zkbar, zkfoo2 zkbar2");

            clone.getKafka().setOneInstancePerNode(false);
            clone.getKafka().setColocateWithZookeeper(false);
            clone.getExporter().setColocateWithZookeeper(false);

            kafkaCluster.setKafkaConfiguration(clone);

            ManagedKafka mk = exampleManagedKafka("60Gi");

            Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

            diffToExpected(kafka, "/expected/custom-config-strimzi.yml");
        } finally {
            kafkaCluster.setKafkaConfiguration(config);
        }
    }

    @Test
    void testKafkaInstanceConfigurationSerialization() throws IOException {
        KafkaInstanceConfiguration config = kafkaCluster.getKafkaConfiguration();
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaInstanceConfiguration clone = objectMapper.readValue(objectMapper.writeValueAsString(config), KafkaInstanceConfiguration.class);
        clone.getKafka().setConnectionAttemptsPerSec(300);
        clone.getKafka().setReplicas(4);
        clone.getKafka().setContainerMemory("2Gi");
        clone.getKafka().setJvmXx("foo bar, foo2 bar2");

        clone.getZookeeper().setReplicas(5);
        clone.getZookeeper().setContainerMemory("11Gi");
        clone.getZookeeper().setJvmXx("zkfoo zkbar, zkfoo2 zkbar2");

        Map<String, String> propertyMap = clone.toMap(false);
        assertEquals("4", propertyMap.get("managedkafka.kafka.replicas"));
        assertEquals("2Gi", propertyMap.get("managedkafka.kafka.container-memory"));
        assertEquals("foo bar, foo2 bar2", propertyMap.get("managedkafka.kafka.jvm-xx"));

        assertEquals("5", propertyMap.get("managedkafka.zookeeper.replicas"));
        assertEquals("11Gi", propertyMap.get("managedkafka.zookeeper.container-memory"));
        assertEquals("zkfoo zkbar, zkfoo2 zkbar2", propertyMap.get("managedkafka.zookeeper.jvm-xx"));

    }

    @Test
    void testManagedKafkaToKafkaBrokerPerNode() throws IOException {
        KafkaInstanceConfiguration config = kafkaCluster.getKafkaConfiguration();
        config.getKafka().setOneInstancePerNode(true);
        config.getKafka().setColocateWithZookeeper(true);
        config.getExporter().setColocateWithZookeeper(true);
        try {
            ManagedKafka mk = exampleManagedKafka("60Gi");
            Kafka kafka = kafkaCluster.kafkaFrom(mk, null);
            diffToExpected(kafka.getSpec().getKafka().getTemplate(), "/expected/broker-per-node-kafka.yml");
            diffToExpected(kafka.getSpec().getKafkaExporter().getTemplate(), "/expected/broker-per-node-exporter.yml");
            diffToExpected(kafka.getSpec().getZookeeper().getTemplate(), "/expected/broker-per-node-zookeeper.yml");
        } finally {
            config.getKafka().setOneInstancePerNode(false);
            config.getKafka().setColocateWithZookeeper(false);
            config.getExporter().setColocateWithZookeeper(false);
        }
    }

    static JsonNode diffToExpected(Object kafka, String expected) throws IOException, JsonProcessingException, JsonMappingException {
        return diffToExpected(kafka, expected, "[]");
    }

    static JsonNode diffToExpected(Object kafka, String expected, String diff) throws IOException, JsonProcessingException, JsonMappingException {
        ObjectMapper objectMapper = Serialization.yamlMapper();
        JsonNode file1 = objectMapper.readTree(KafkaClusterTest.class.getResourceAsStream(expected));
        String yaml = Serialization.asYaml(kafka);
        JsonNode file2 = objectMapper.readTree(yaml);
        JsonNode patch = JsonDiff.asJson(file1, file2);
        assertEquals(diff, patch.toString(), yaml);
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

    @Test
    void testExistingStorageClassOverridesDontGetUpdated() {
        ManagedKafka mk = exampleManagedKafka("60Gi");

        Kafka defaultKafka = kafkaCluster.kafkaFrom(mk, null);
        JbodStorage defaultKafkaStorage = (JbodStorage) defaultKafka.getSpec().getKafka().getStorage();
        PersistentClaimStorage defaultZookeeperStorage = (PersistentClaimStorage) defaultKafka.getSpec().getZookeeper().getStorage();

        JbodStorage kafkaStorageWithOverrides = new JbodStorageBuilder(defaultKafkaStorage)
                .withVolumes(defaultKafkaStorage.getVolumes().stream()
                        .map(v -> {
                            PersistentClaimStorage pcs = (PersistentClaimStorage) v;
                            pcs.setStorageClass(null);
                            pcs.setOverrides(buildStorageOverrides());
                            return pcs;
                        })
                        .collect(Collectors.toList()))
                .build();

        PersistentClaimStorage zookeeperStorageWithOverrides = new PersistentClaimStorageBuilder(defaultZookeeperStorage)
                .withStorageClass(null)
                .withOverrides(buildStorageOverrides())
                .build();

        Kafka kafkaWithOverrides = new KafkaBuilder(defaultKafka)
                .editSpec()
                .editKafka()
                .withStorage(kafkaStorageWithOverrides)
                .endKafka()
                .editZookeeper()
                .withStorage(zookeeperStorageWithOverrides)
                .endZookeeper()
                .endSpec()
                .build();

        Kafka reconciledKafka = kafkaCluster.kafkaFrom(mk, kafkaWithOverrides);

        assertNull(((PersistentClaimStorage) reconciledKafka.getSpec().getZookeeper().getStorage()).getStorageClass());
        assertEquals(buildStorageOverrides(), ((PersistentClaimStorage) reconciledKafka.getSpec().getZookeeper().getStorage()).getOverrides());

        ((JbodStorage)reconciledKafka.getSpec().getKafka().getStorage()).getVolumes().stream().forEach(v -> {
            assertNull(((PersistentClaimStorage)v).getStorageClass());
            assertEquals(buildStorageOverrides(), ((PersistentClaimStorage)v).getOverrides());
        });
    }

    @ParameterizedTest
    @CsvSource({
        ",       299000", // Default to 4m 59s
        "-1,          0", // No less than zero
        "0,           0",
        "1,           1",
        "299000, 299000"
    })
    void testManagedKafkaMaximumSessionLifetimeMapping(Long maximumSessionLifetime, long maxReauthMs) throws IOException {
        ManagedKafka mk = exampleManagedKafka("60Gi");
        mk.getSpec().getOauth().setMaximumSessionLifetime(maximumSessionLifetime);

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);
        assertEquals(maxReauthMs, kafka.getSpec().getKafka().getConfig().get("connections.max.reauth.ms"));
    }

    private List<PersistentClaimStorageOverride> buildStorageOverrides() {
        int num = 3;
        List<String> storageClasses = List.of("kas-us-east-1a", "kas-us-east-1b", "kas-us-east-1c");

        List<PersistentClaimStorageOverride> overrides = new ArrayList<>(num);
        for (int i = 0; i < num; i++) {
            overrides.add(
                    new PersistentClaimStorageOverrideBuilder()
                    .withBroker(i)
                    .withStorageClass(storageClasses.get(i % storageClasses.size()))
                    .build());
        }
        return overrides;
    }
}
