package org.bf2.operator.operands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.zjsonpatch.JsonDiff;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationOAuth;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.status.ConditionBuilder;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.api.kafka.model.storage.JbodStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverride;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverrideBuilder;
import org.bf2.operator.managers.DrainCleanerManager;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.managers.IngressControllerManager;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.operands.KafkaInstanceConfigurations.InstanceType;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
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
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.bf2.operator.utils.ManagedKafkaUtils.dummyManagedKafka;
import static org.bf2.operator.utils.ManagedKafkaUtils.exampleManagedKafka;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class KafkaClusterTest {

    @Inject
    KubernetesClient client;

    @Inject
    KafkaCluster kafkaCluster;

    @Inject
    InformerManager informerManager;

    @KubernetesTestServer
    KubernetesServer kubernetesServer;

    @Inject
    KafkaInstanceConfigurations configs;

    @BeforeEach
    void beforeEach() {
        informerManager.createKafkaInformer();

        IngressControllerManager controllerManager = Mockito.mock(IngressControllerManager.class);

        Mockito.when(controllerManager.getRouteMatchLabels()).thenReturn(Map.of(
                "managedkafka.bf2.org/kas-multi-zone", "true",
                "managedkafka.bf2.org/kas-zone0", "true",
                "managedkafka.bf2.org/kas-zone1", "true",
                "managedkafka.bf2.org/kas-zone2", "true"));

        QuarkusMock.installMockForType(controllerManager, IngressControllerManager.class);
    }

    @Test
    void testManagedKafkaToKafka() throws IOException {
        alternativeConfig(config -> {
            config.getKafka().setOneInstancePerNode(false);
            config.getKafka().setColocateWithZookeeper(false);
            config.getExporter().setColocateWithZookeeper(false);
        });

        ManagedKafka mk = exampleManagedKafka("60Gi");

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        diffToExpected(kafka, "/expected/strimzi.yml");
    }

    private void alternativeConfig(Consumer<KafkaInstanceConfiguration> configModifier) throws JsonProcessingException, JsonMappingException {
        KafkaInstanceConfiguration config = configs.getConfig(InstanceType.STANDARD);

        ObjectMapper objectMapper = new ObjectMapper();
        KafkaInstanceConfiguration clone = objectMapper.readValue(objectMapper.writeValueAsString(config), KafkaInstanceConfiguration.class);
        configModifier.accept(clone);

        KafkaInstanceConfigurations mock = Mockito.mock(KafkaInstanceConfigurations.class);
        Mockito.when(mock.getConfig(Mockito.<ManagedKafka>any())).thenReturn(clone);
        QuarkusMock.installMockForType(mock, KafkaInstanceConfigurations.class);
    }

    @Test
    void testManagedKafkaToKafkaWithSizeChanges() throws IOException {
        alternativeConfig(clone -> {
            clone.getKafka().setOneInstancePerNode(false);
            clone.getKafka().setColocateWithZookeeper(false);
            clone.getExporter().setColocateWithZookeeper(false);
        });
        Kafka kafka = kafkaCluster.kafkaFrom(exampleManagedKafka("60Gi"), null);

        Kafka reduced = kafkaCluster.kafkaFrom(exampleManagedKafka("40Gi"), kafka);

        // storage should not change to a smaller size, but the lower limits should be enforced
        diffToExpected(reduced, "/expected/strimzi.yml", "[{\"op\":\"replace\",\"path\":\"/spec/kafka/config/client.quota.callback.static.storage.soft\",\"value\":\"14316557653\"},{\"op\":\"replace\",\"path\":\"/spec/kafka/config/client.quota.callback.static.storage.hard\",\"value\":\"14316557653\"}]");

        Kafka larger = kafkaCluster.kafkaFrom(exampleManagedKafka("80Gi"), kafka);

        // storage should change to a larger size with the higher limits.
        diffToExpected(larger, "/expected/strimzi.yml", "[{\"op\":\"replace\",\"path\":\"/spec/kafka/config/client.quota.callback.static.storage.soft\",\"value\":\"28633115306\"},{\"op\":\"replace\",\"path\":\"/spec/kafka/config/client.quota.callback.static.storage.hard\",\"value\":\"28633115306\"},{\"op\":\"replace\",\"path\":\"/spec/kafka/storage/volumes/0/size\",\"value\":\"41383100373\"}]");
    }

    @Test
    void hardLimitShouldBeAtCustomerStorage() throws IOException {
        //Given
        alternativeConfig(clone -> {
            clone.getKafka().setOneInstancePerNode(false);
            clone.getKafka().setColocateWithZookeeper(false);
            clone.getKafka().setEnableQuota(true);
            clone.getExporter().setColocateWithZookeeper(false);
        });
        final ManagedKafka managedKafka = exampleManagedKafka("2Ti");

        //When
        Kafka kafka = kafkaCluster.kafkaFrom(managedKafka, null);

        //Then
        final Map<String, Object> config = kafka.getSpec().getKafka().getConfig();
        final long brokerCapacity = getBrokerCapacity(managedKafka, kafka);

        assertLimitAt("client.quota.callback.static.storage.hard", config, brokerCapacity);
    }

    @Test
    void hardLimitShouldBeWithinPvcStorage() throws IOException {
        //Given
        alternativeConfig(clone -> {
            clone.getKafka().setOneInstancePerNode(false);
            clone.getKafka().setColocateWithZookeeper(false);
            clone.getKafka().setEnableQuota(true);
            clone.getExporter().setColocateWithZookeeper(false);
        });
        final ManagedKafka managedKafka = exampleManagedKafka("2Ti");

        //When
        Kafka kafka = kafkaCluster.kafkaFrom(managedKafka, null);

        //Then
        final Map<String, Object> config = kafka.getSpec().getKafka().getConfig();
        final long volumeSizeInBytes = getVolumeSizeInBytes(kafka);

        assertLimitUnder("client.quota.callback.static.storage.hard", config, volumeSizeInBytes);
    }

    @Test
    void softLimitShouldBeEqualToHardLimit() throws IOException {
        //Given
        alternativeConfig(clone -> {
            clone.getKafka().setOneInstancePerNode(false);
            clone.getKafka().setColocateWithZookeeper(false);
            clone.getKafka().setEnableQuota(true);
            clone.getExporter().setColocateWithZookeeper(false);
        });
        final ManagedKafka managedKafka = exampleManagedKafka("2Ti");

        //When
        Kafka kafka = kafkaCluster.kafkaFrom(managedKafka, null);

        //Then
        final Map<String, Object> config = kafka.getSpec().getKafka().getConfig();
        final long volumeSizeInBytes = getVolumeSizeInBytes(kafka);

        final long actualSoftLimit = assertLimitUnder("client.quota.callback.static.storage.soft", config, volumeSizeInBytes);
        final long actualHardLimit = assertLimitUnder("client.quota.callback.static.storage.hard", config, volumeSizeInBytes);
        assertEquals(actualSoftLimit, actualHardLimit);
    }

    private long getVolumeSizeInBytes(Kafka kafka) {
        final JbodStorage storage = (JbodStorage) kafka.getSpec().getKafka().getStorage();
        final PersistentClaimStorage pvc = (PersistentClaimStorage) storage.getVolumes().get(0);
        return Long.parseLong(pvc.getSize());
    }

    private long getBrokerCapacity(ManagedKafka managedKafka, Kafka kafka) {
        final Quantity maxDataRetentionSize = managedKafka.getSpec().getCapacity().getMaxDataRetentionSize();
        final long clusterCapacity = Quantity.getAmountInBytes(maxDataRetentionSize).longValue();
        final int replicas = kafka.getSpec().getKafka().getReplicas();
        return clusterCapacity / replicas;
    }

    private long assertLimitUnder(String key, Map<String, Object> config, long expected) {
        final long actualLimit = Long.parseLong(String.valueOf(config.get(key)));
        assertTrue(actualLimit < expected, "expected (" + key + ") " + actualLimit + " to be smaller than " + expected);
        return actualLimit;
    }

    private void assertLimitAt(String key, Map<String, Object> config, long expected) {
        final long actualLimit = Long.parseLong(String.valueOf(config.get(key)));
        assertEquals(actualLimit, expected, "expected (" + key + ") " + actualLimit + " to be smaller than " + expected);
    }

    @Test
    void testDeveloperManagedKafka() throws IOException {
        ManagedKafka mk = exampleManagedKafka("60Gi");
        mk.getMetadata().setLabels(Map.of(ManagedKafka.PROFILE_TYPE, KafkaInstanceConfigurations.InstanceType.DEVELOPER.lowerName));
        mk.getSpec().getCapacity().setMaxPartitions(100);

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        diffToExpected(kafka, "/expected/developer-strimzi.yml");
    }

    @Test
    void testManagedKafkaToKafkaWithCustomConfiguration() throws IOException {
        ManagedKafka mk = exampleManagedKafka("60Gi");
        mk.getSpec().getCapacity().setMaxPartitions(2*configs.getConfig(InstanceType.STANDARD).getKafka().getPartitionCapacity());

        alternativeConfig(clone -> {
            clone.getKafka().setConnectionAttemptsPerSec(300);
            clone.getKafka().setContainerMemory("2Gi");
            clone.getKafka().setJvmXx("foo bar, foo2 bar2");

            clone.getZookeeper().setReplicas(5);
            clone.getZookeeper().setContainerMemory("11Gi");
            clone.getZookeeper().setJvmXx("zkfoo zkbar, zkfoo2 zkbar2");

            clone.getKafka().setOneInstancePerNode(false);
            clone.getKafka().setColocateWithZookeeper(false);
            clone.getExporter().setColocateWithZookeeper(false);
        });

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        diffToExpected(kafka, "/expected/custom-config-strimzi.yml");
    }

    @Test
    void testScalingAndReplicationFactor() throws IOException {
        alternativeConfig(config -> {
            config.getKafka().setScalingAndReplicationFactor(1);
        });

        ManagedKafka mk = exampleManagedKafka("60Gi");

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        diffToExpected(kafka.getSpec().getKafka().getConfig(), "/expected/scaling-one.yml");
    }

    @Test
    void testKafkaInstanceConfigurationSerialization() throws IOException {
        KafkaInstanceConfiguration config = configs.getConfig(InstanceType.STANDARD);
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaInstanceConfiguration clone = objectMapper.readValue(objectMapper.writeValueAsString(config), KafkaInstanceConfiguration.class);
        clone.getKafka().setConnectionAttemptsPerSec(300);
        clone.getKafka().setContainerMemory("2Gi");
        clone.getKafka().setJvmXx("foo bar, foo2 bar2");

        clone.getZookeeper().setReplicas(5);
        clone.getZookeeper().setContainerMemory("11Gi");
        clone.getZookeeper().setJvmXx("zkfoo zkbar, zkfoo2 zkbar2");

        Map<String, String> propertyMap = clone.toMap(false);
        assertEquals("2Gi", propertyMap.get("managedkafka.kafka.container-memory"));
        assertEquals("foo bar, foo2 bar2", propertyMap.get("managedkafka.kafka.jvm-xx"));

        assertEquals("5", propertyMap.get("managedkafka.zookeeper.replicas"));
        assertEquals("11Gi", propertyMap.get("managedkafka.zookeeper.container-memory"));
        assertEquals("zkfoo zkbar, zkfoo2 zkbar2", propertyMap.get("managedkafka.zookeeper.jvm-xx"));
    }

    @Test
    void testManagedKafkaToKafkaBrokerPerNode() throws IOException {
        alternativeConfig(config -> {
            config.getKafka().setOneInstancePerNode(true);
            config.getKafka().setColocateWithZookeeper(true);
            config.getExporter().setColocateWithZookeeper(true);
        });
        ManagedKafka mk = exampleManagedKafka("60Gi");
        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);
        diffToExpected(kafka.getSpec().getKafka().getTemplate(), "/expected/broker-per-node-kafka.yml");
        diffToExpected(kafka.getSpec().getKafkaExporter().getTemplate(), "/expected/broker-per-node-exporter.yml");
        diffToExpected(kafka.getSpec().getZookeeper().getTemplate(), "/expected/broker-per-node-zookeeper.yml");
    }

    static JsonNode diffToExpected(Object kafka, String expected) throws IOException, JsonProcessingException, JsonMappingException {
        return diffToExpected(kafka, expected, "[]");
    }

    static JsonNode diffToExpected(Object kafka, String expected, String diff) throws IOException, JsonProcessingException, JsonMappingException {
        ObjectMapper objectMapper = Serialization.yamlMapper();
        JsonNode expectedJson = objectMapper.readTree(KafkaClusterTest.class.getResourceAsStream(expected));
        String yaml = Serialization.asYaml(kafka);
        JsonNode actualJson = objectMapper.readTree(yaml);
        JsonNode patch = JsonDiff.asJson(expectedJson, actualJson);
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
    void testStorageCalculations() {
        ManagedKafka mk = exampleManagedKafka("40Gi");
        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);
        long bytes = getBrokerStorageBytes(kafka);
        assertEquals(26350714837L, bytes);

        assertEquals((40*1L<<30)-1, kafkaCluster.unpadBrokerStorage(mk, null,   bytes)*3);
    }

    @Test
    void testFormattedStorageCalculations() {
        ManagedKafka mk = dummyManagedKafka("id");

        long desiredUserStorage = Quantity.getAmountInBytes(Quantity.parse("1Gi")).longValue();
        long formattingOverhead = kafkaCluster.calculateFormatOverheadFromFormattedSize(mk, desiredUserStorage);
        long physicalStorage = desiredUserStorage + formattingOverhead;
        assertTrue(physicalStorage > desiredUserStorage);
        long formattingOverheadFromPhysicalStorage = kafkaCluster.calculateFormatOverheadFromUnformattedSize(mk, physicalStorage);
        assertEquals(formattingOverhead, formattingOverheadFromPhysicalStorage);
    }

    private long getBrokerStorageBytes(Kafka kafka) {
        JbodStorage storage = (JbodStorage)kafka.getSpec().getKafka().getStorage();
        PersistentClaimStorage pcs = (PersistentClaimStorage)storage.getVolumes().get(0);
        Quantity quantity = Quantity.parse(pcs.getSize());
        return Quantity.getAmountInBytes(quantity).longValue();
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

    @Test
    void storageCalculation() throws InterruptedException {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        InformerManager informerManager = Mockito.mock(InformerManager.class);

        QuarkusMock.installMockForType(informerManager, InformerManager.class);
        Mockito.when(informerManager.getPvcsInNamespace(Mockito.anyString())).thenReturn(List.of());

        // there's no pvcs, should be 0
        assertEquals("0", kafkaCluster.calculateRetentionSize(mk).getAmount());

        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder().withNewStatus().addToCapacity("storage", Quantity.parse("361Gi")).endStatus().build();
        Mockito.when(informerManager.getPvcsInNamespace(Mockito.anyString())).thenReturn(List.of(pvc, pvc, pvc));

        // should be the sum in Gi, less the padding
        assertEquals("1000", kafkaCluster.calculateRetentionSize(mk).getAmount());
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

    @Test
    void pausedUnknownStatus() throws InterruptedException {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);

        InformerManager informer = Mockito.mock(InformerManager.class);
        Kafka kafka = new KafkaBuilder(this.kafkaCluster.kafkaFrom(mk, null))
                .editMetadata().withAnnotations(Map.of(StrimziManager.STRIMZI_PAUSE_REASON_ANNOTATION, "custom")).endMetadata()
                .withNewStatus()
                .withConditions(new ConditionBuilder().withType("ReconciliationPaused").withStatus("True").build())
                .endStatus().build();

        Mockito.when(informer.getLocalKafka(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(kafka);
        QuarkusMock.installMockForType(informer, InformerManager.class);

        OperandReadiness readiness = this.kafkaCluster.getReadiness(mk);
        assertEquals(Status.Unknown, readiness.getStatus());
        assertEquals(Reason.Paused, readiness.getReason());
        assertEquals("Kafka mk-1 is paused for an unknown reason", readiness.getMessage());
    }

    @Test
    void testOAuthClientCredentialsAbsentWhenRemoved() throws IOException {
        BiFunction<Kafka, String, GenericKafkaListener> getListener =
                (k, n) -> k.getSpec().getKafka().getListeners().stream().filter(l -> l.getName().equals(n)).findFirst().orElse(null);

        ManagedKafka mk = exampleManagedKafka("60Gi");
        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        // Credentials present from default example MK
        var extListener1 = getListener.apply(kafka, AbstractKafkaCluster.EXTERNAL_LISTENER_NAME);
        assertNotNull(((KafkaListenerAuthenticationOAuth) extListener1.getAuth()).getClientId());
        assertNotNull(((KafkaListenerAuthenticationOAuth) extListener1.getAuth()).getClientSecret());

        var oauthListener1 = getListener.apply(kafka, "oauth");
        assertNotNull(((KafkaListenerAuthenticationOAuth) oauthListener1.getAuth()).getClientId());
        assertNotNull(((KafkaListenerAuthenticationOAuth) oauthListener1.getAuth()).getClientSecret());

        // Credentials absent when removed from default example MK
        mk.getSpec().getOauth().setClientId(null);
        mk.getSpec().getOauth().setClientSecret(null);
        kafka = kafkaCluster.kafkaFrom(mk, null);

        var extListener2 = getListener.apply(kafka, AbstractKafkaCluster.EXTERNAL_LISTENER_NAME);
        assertNull(((KafkaListenerAuthenticationOAuth) extListener2.getAuth()).getClientId());
        assertNull(((KafkaListenerAuthenticationOAuth) extListener2.getAuth()).getClientSecret());

        var oauthListener2 = getListener.apply(kafka, "oauth");
        assertNull(((KafkaListenerAuthenticationOAuth) oauthListener2.getAuth()).getClientId());
        assertNull(((KafkaListenerAuthenticationOAuth) oauthListener2.getAuth()).getClientSecret());
    }

}
