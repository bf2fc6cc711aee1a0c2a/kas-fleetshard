package org.bf2.operator.operands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.zjsonpatch.JsonDiff;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.StrimziPodSet;
import io.strimzi.api.kafka.model.StrimziPodSetBuilder;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationOAuth;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.status.ConditionBuilder;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.api.kafka.model.storage.JbodStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverride;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverrideBuilder;
import org.bf2.common.OperandUtils;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.ManagedKafkaKeys.Annotations;
import org.bf2.operator.managers.DrainCleanerManager;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.managers.IngressControllerManager;
import org.bf2.operator.managers.OperandOverrideManager;
import org.bf2.operator.managers.OperandOverrideManager.Kafka.ListenerOverride;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.operands.KafkaInstanceConfigurations.InstanceType;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.bf2.operator.utils.ManagedKafkaUtils.dummyManagedKafka;
import static org.bf2.operator.utils.ManagedKafkaUtils.exampleManagedKafka;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

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

    @InjectMock
    OperandOverrideManager overrideManager;

    @BeforeEach
    void beforeEach() {
        informerManager.createKafkaInformer();

        IngressControllerManager controllerManager = Mockito.mock(IngressControllerManager.class);

        Mockito.when(controllerManager.getRouteMatchLabels()).thenReturn(Map.of(
                ManagedKafkaKeys.Labels.KAS_MULTI_ZONE, "true",
                ManagedKafkaKeys.forKey("kas-zone0"), "true",
                ManagedKafkaKeys.forKey("kas-zone1"), "true",
                ManagedKafkaKeys.forKey("kas-zone2"), "true"));

        QuarkusMock.installMockForType(controllerManager, IngressControllerManager.class);

        // clean out all deployments
        client.resources(Kafka.class).inAnyNamespace().delete();
        client.apps().deployments().inAnyNamespace().delete();
        client.pods().inAnyNamespace().delete();
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

    @Test
    void testManagedKafkaToReserved() throws IOException {
        ManagedKafka mk = exampleManagedKafka("60Gi");

        validateReserved(mk, "/expected/reserved.yml");
    }

    @Test
    void testManagedKafkaToReservedWithCruiseControl() throws IOException {
        ManagedKafka mk = exampleManagedKafka("60Gi");
        mk.getSpec().getCapacity().setMaxPartitions(3000);

        validateReserved(mk, "/expected/reserved-cruisecontrol.yml");
    }

    private void validateReserved(ManagedKafka mk, String expected) throws IOException, JsonProcessingException, JsonMappingException {
        mk.getMetadata().setNamespace("reserved");
        mk = new ManagedKafkaBuilder(mk).editMetadata()
                .addToLabels(ManagedKafka.DEPLOYMENT_TYPE, ManagedKafka.RESERVED_DEPLOYMENT_TYPE)
                .endMetadata()
                .build();

        kafkaCluster.createOrUpdate(mk);
        List<Deployment> deployments = client.apps()
                .deployments()
                .inNamespace(mk.getMetadata().getNamespace())
                .list()
                .getItems();

        diffToExpected(deployments.stream()
                .map(d -> new DeploymentBuilder(d).editMetadata()
                        .withCreationTimestamp(null)
                        .withUid(null)
                        .withResourceVersion(null)
                        .endMetadata()
                        .build())
                .sorted((d1, d2) -> d1.getMetadata().getName().compareTo(d2.getMetadata().getName()))
                .toArray(), expected);

        // after a status update or other change we'll attempt to reconcile
        // again.  make sure that we end up in the same state
        kafkaCluster.createOrUpdate(mk);
        List<Deployment> deployments1 = client.apps()
                .deployments()
                .inNamespace(mk.getMetadata().getNamespace())
                .list()
                .getItems();
        assertEquals(new HashSet<>(deployments), new HashSet<>(deployments1));
    }

    @Test
    void testManagedKafkaToKafka_StreamingUnitsTwo() throws IOException {
        alternativeConfig(config -> {
            config.getKafka().setOneInstancePerNode(false);
            config.getKafka().setColocateWithZookeeper(false);
            config.getExporter().setColocateWithZookeeper(false);
        });

        ManagedKafka mk = exampleManagedKafka("2Ti");
        mk.getSpec().getCapacity().setIngressPerSec(Quantity.parse("100Mi"));
        mk.getSpec().getCapacity().setEgressPerSec(Quantity.parse("200Mi"));
        mk.getSpec().getCapacity().setMaxPartitions(3000);
        mk.getSpec().getCapacity().setTotalMaxConnections(6000);
        mk.getSpec().getCapacity().setTotalMaxConnections(200);


        ImagePullSecretManager imagePullSecretManager = Mockito.mock(ImagePullSecretManager.class);
        Mockito.when(imagePullSecretManager.getOperatorImagePullSecrets(Mockito.any())).thenReturn(
            List.of(new LocalObjectReferenceBuilder().withName("myimage:0.0.1").build()));

        QuarkusMock.installMockForType(imagePullSecretManager, ImagePullSecretManager.class);

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        diffToExpected(kafka, "/expected/strimzi_su2.yml");
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
    void testElevatedPriority() throws IOException {
        final ManagedKafka managedKafka = exampleManagedKafka("2Ti");

        Mockito.when(overrideManager.useElevatedPriority(Mockito.anyString())).thenReturn(true);

        Kafka kafka = kafkaCluster.kafkaFrom(managedKafka, null);

        assertEquals(KafkaCluster.KAS_FLEETSHARD_MEDIUM_PRIORITY, kafka.getSpec().getKafka().getTemplate().getPod().getPriorityClassName());
        assertEquals(KafkaCluster.KAS_FLEETSHARD_MEDIUM_PRIORITY, kafka.getSpec().getZookeeper().getTemplate().getPod().getPriorityClassName());
    }

    @Test
    void testNodeAffinity() throws IOException {
        final ManagedKafka managedKafka = exampleManagedKafka("2Ti");

        OperandTestUtils.useNodeAffinity(managedKafka);

        Mockito.when(overrideManager.useDynamicScalingScheduling(Mockito.anyString())).thenReturn(true);

        //When
        Kafka kafka = kafkaCluster.kafkaFrom(managedKafka, null);

        //Then
        Affinity affinity = kafka.getSpec().getKafka().getTemplate().getPod().getAffinity();
        String expected = "---\n"
                + "nodeAffinity:\n"
                + "  requiredDuringSchedulingIgnoredDuringExecution:\n"
                + "    nodeSelectorTerms:\n"
                + "    - matchExpressions:\n"
                + "      - key: \"bf2.org/kafkaInstanceProfileType\"\n"
                + "        operator: \"In\"\n"
                + "        values:\n"
                + "        - \"standard\"\n"
                + "podAffinity:\n"
                + "  preferredDuringSchedulingIgnoredDuringExecution:\n"
                + "  - podAffinityTerm:\n"
                + "      labelSelector:\n"
                + "        matchExpressions:\n"
                + "        - key: \"strimzi.io/name\"\n"
                + "          operator: \"In\"\n"
                + "          values:\n"
                + "          - \"test-mk-zookeeper\"\n"
                + "      topologyKey: \"kubernetes.io/hostname\"\n"
                + "    weight: 100\n";
        assertEquals(expected, Serialization.asYaml(affinity));

        affinity = kafka.getSpec().getKafkaExporter().getTemplate().getPod().getAffinity();
        assertEquals(expected, Serialization.asYaml(affinity));

        assertEquals(affinity.getNodeAffinity(),
                kafka.getSpec().getZookeeper().getTemplate().getPod().getAffinity().getNodeAffinity());

        // try with the value disabled as well
        Mockito.when(overrideManager.useDynamicScalingScheduling(Mockito.anyString())).thenReturn(false);

        kafka = kafkaCluster.kafkaFrom(managedKafka, null);

        affinity = kafka.getSpec().getKafka().getTemplate().getPod().getAffinity();
        expected = "---\n"
                + "podAffinity:\n"
                + "  preferredDuringSchedulingIgnoredDuringExecution:\n"
                + "  - podAffinityTerm:\n"
                + "      labelSelector:\n"
                + "        matchExpressions:\n"
                + "        - key: \"strimzi.io/name\"\n"
                + "          operator: \"In\"\n"
                + "          values:\n"
                + "          - \"test-mk-zookeeper\"\n"
                + "      topologyKey: \"kubernetes.io/hostname\"\n"
                + "    weight: 100\n";
        assertEquals(expected, Serialization.asYaml(affinity));
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

        String strimzi = mk.getSpec().getVersions().getStrimzi();
        Map<String, Object> brokerConfig = new HashMap<>();
        brokerConfig.put("broker.foo", "bar");
        brokerConfig.put("auto.create.topics.enable", null);

        var externalListenerOverride = new ListenerOverride();
        externalListenerOverride.getAuth().setAdditionalProperty("jwksExpirySeconds", 3601);

        var oauthListenerOverride = new ListenerOverride();
        oauthListenerOverride.getAuth().setAdditionalProperty("jwksExpirySeconds", 3602);

        OperandOverrideManager.Kafka kafkaOverride = new OperandOverrideManager.Kafka();
        kafkaOverride.setBrokerConfig(brokerConfig);
        kafkaOverride.setEnv(List.of(new EnvVar("FOO", "BAR", null)));
        kafkaOverride.setListeners(Map.of("external", externalListenerOverride, "oauth", oauthListenerOverride));

        when(overrideManager.getKafkaOverride(strimzi)).thenReturn(kafkaOverride);
        when(overrideManager.getCanaryImage(strimzi)).thenReturn("quay.io/mk-ci-cd/strimzi-canary:0.2.0-220111183833");
        when(overrideManager.getCanaryInitImage(strimzi)).thenReturn("quay.io/mk-ci-cd/strimzi-canary:0.2.0-220111183833");

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

            clone.getCruiseControl().setEnabled(false);
        });

        Kafka kafka = kafkaCluster.kafkaFrom(mk, null);

        diffToExpected(kafka, "/expected/custom-config-strimzi.yml");
    }

    @Test
    void testScalingAndReplicationFactor() throws IOException {
        alternativeConfig(config -> {
            config.getKafka().setScalingAndReplicationFactor(1);
            config.getKafka().setTopicConfigPolicyEnforced(false);
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

    static JsonNode diffToExpected(Object obj, String expected) throws IOException, JsonProcessingException, JsonMappingException {
        return diffToExpected(obj, expected, "[]");
    }

    static JsonNode diffToExpected(Object obj, String expected, String diff) throws IOException, JsonProcessingException, JsonMappingException {
        ObjectMapper objectMapper = Serialization.yamlMapper();
        JsonNode expectedJson = objectMapper.readTree(KafkaClusterTest.class.getResourceAsStream(expected));
        String yaml = Serialization.asYaml(obj);
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

        ManagedKafka dev = new ManagedKafkaBuilder(mk).editMetadata().addToLabels(ManagedKafka.PROFILE_TYPE, KafkaInstanceConfigurations.InstanceType.DEVELOPER.lowerName).endMetadata().build();
        Kafka kafkaDev = kafkaCluster.kafkaFrom(dev, null);

        assertNull(kafkaDev.getSpec().getKafka().getTemplate().getPodDisruptionBudget());
        assertNull(kafkaDev.getSpec().getZookeeper().getTemplate().getPodDisruptionBudget());
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

    @ParameterizedTest
    @CsvSource({
        "     , true, custom         ,  ,  , ReconciliationPaused, True ,          , 0,      ,    , Unknown   , Paused          , 'Kafka mk-1 is paused for an unknown reason'",
        "     ,     ,                ,  ,  , NotReady            , True , Creating , 0,      ,    , False     , Installing      , ",
        "     ,     ,                ,  ,  , NotReady            , True , Something, 0,      ,    , False     , Error           , ",
        "     , true, strimziupdating,  ,  , ReconciliationPaused, True ,          , 0,      ,    , True      , StrimziUpdating , 'Updating Strimzi version'",
        "true , true,                , 1,  , ReconciliationPaused, True ,          , 0,      ,    , True      , KafkaUpdating   , 'Updating Kafka version'",
        "true , true,                , 1, 2, ReconciliationPaused, True ,          , 0,      ,    , True      , KafkaUpdating   , 'Updating Kafka version'",
        "false,     ,                ,  ,  ,                     ,      ,          , 2, 3.2.0,    , True      , KafkaUpdating   , 'Updating Kafka version'",
        "true , true,                ,  ,  , ReconciliationPaused, True ,          , 2,      , 3.2, True      , KafkaIbpUpdating, 'Updating Kafka IBP version'",
        "true , true,                ,  ,  , ReconciliationPaused, True ,          , 0,      ,    , False     , Suspended       , ",
        "false,     ,                ,  ,  ,                     ,      ,          , 0,      ,    , False     , Installing      , 'Kafka mk-1 is not providing status'",
    })
    void testReadiness(String suspendedValue,
            String pauseValue,
            String pauseReason,
            Long kafkaUpgradeStart,
            Long kafkaUpgradeEnd,
            String conditionType,
            String conditionStatus,
            String conditionReason,
            int kafkaPodCount,
            String kafkaPodVersion,
            String kafkaPodIbpVersion,
            Status expStatus,
            Reason expReason,
            String expMessage) {

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getSpec().getVersions().setKafkaIbp(AbstractKafkaCluster.getKafkaIbpVersion(mk.getSpec().getVersions().getKafka()));
        String ns = mk.getMetadata().getNamespace();
        String name = mk.getMetadata().getName();

        InformerManager informer = Mockito.mock(InformerManager.class);
        KafkaBuilder kafka = new KafkaBuilder(this.kafkaCluster.kafkaFrom(mk, null));

        if (kafkaUpgradeStart != null) {
            kafka.editMetadata().addToAnnotations(
                    ManagedKafkaKeys.Annotations.KAFKA_UPGRADE_START_TIMESTAMP,
                    Instant.ofEpochMilli(kafkaUpgradeStart).toString()).endMetadata();
        }

        if (kafkaUpgradeEnd != null) {
            kafka.editMetadata().addToAnnotations(
                    ManagedKafkaKeys.Annotations.KAFKA_UPGRADE_END_TIMESTAMP,
                    Instant.ofEpochMilli(kafkaUpgradeEnd).toString()).endMetadata();
        }

        if (suspendedValue != null) {
            mk.getMetadata().setLabels(Map.of(ManagedKafka.SUSPENDED_INSTANCE, suspendedValue));
        }

        if (pauseValue != null) {
            kafka.editOrNewMetadata()
                .addToAnnotations(StrimziManager.STRIMZI_PAUSE_RECONCILE_ANNOTATION, pauseValue)
                .endMetadata();
        }

        if (pauseReason != null) {
            kafka.editOrNewMetadata()
                .addToAnnotations(Annotations.STRIMZI_PAUSE_REASON, pauseReason)
                .endMetadata();
        }

        if (conditionType != null || conditionStatus != null || conditionReason != null) {
            kafka.withNewStatus()
                .withConditions(new ConditionBuilder()
                        .withType(conditionType)
                        .withStatus(conditionStatus)
                        .withReason(conditionReason)
                        .build())
                .endStatus();
        }

        Mockito.when(informer.getLocalKafka(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(kafka.build());
        QuarkusMock.installMockForType(informer, InformerManager.class);

        for (int i = 0; i < kafkaPodCount; i++) {
            PodBuilder pod = new PodBuilder()
                    .withNewMetadata()
                        .withName(name + "-kafka-" + i)
                        .addToLabels("strimzi.io/name", name + "-kafka")
                    .endMetadata()
                    .withNewSpec()
                    .endSpec();

            if (kafkaPodVersion != null) {
                pod.editMetadata()
                    .addToAnnotations("strimzi.io/kafka-version", kafkaPodVersion)
                    .endMetadata();
            }

            if (kafkaPodIbpVersion != null) {
                pod.editMetadata()
                    .addToAnnotations("strimzi.io/inter-broker-protocol-version", kafkaPodIbpVersion)
                    .endMetadata();
            }

            client.pods().inNamespace(ns).create(pod.build());
        }

        OperandReadiness readiness = this.kafkaCluster.getReadiness(mk);
        assertEquals(expStatus, readiness.getStatus());
        assertEquals(expReason, readiness.getReason());
        assertEquals(expMessage, readiness.getMessage());
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

    @ParameterizedTest
    @CsvSource({
        "true , ReconciliationPaused, True , true",
        "true , ReconciliationPaused, False, false",
        "false, ReconciliationPaused, True , false",
        "true , Ready               , True , false",
        "true ,                     ,      , false",
        "true ,                     , True , false",
    })
    void testIsReconciliationPaused(String pauseValue, String conditionType, String conditionValue, boolean expected) {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        InformerManager informer = Mockito.mock(InformerManager.class);

        KafkaBuilder kafka = new KafkaBuilder(kafkaCluster.kafkaFrom(mk, null))
                .editMetadata()
                    .addToAnnotations(StrimziManager.STRIMZI_PAUSE_RECONCILE_ANNOTATION, pauseValue)
                .endMetadata();

        if (conditionType != null || conditionValue != null) {
            kafka.withNewStatus()
                    .withConditions(new ConditionBuilder().withType(conditionType).withStatus(conditionValue).build())
                .endStatus();
        }

        Mockito.when(informer.getLocalKafka(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(kafka.build());
        QuarkusMock.installMockForType(informer, InformerManager.class);

        assertEquals(expected, kafkaCluster.isReconciliationPaused(mk));
    }

    @Test
    void testHasClusterId() {
        assertTrue(KafkaCluster.hasClusterId(new KafkaBuilder().withNewStatus().withClusterId("all-good").endStatus().build()));
        assertFalse(KafkaCluster.hasClusterId(new KafkaBuilder().build()));
        assertFalse(KafkaCluster.hasClusterId(null));
    }

    @Test
    void testClusterSuspendsWithAnnotationPresentAndNoUpdatesInProgress() {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        String ns = mk.getMetadata().getNamespace();
        String name = mk.getMetadata().getName();

        InformerManager informer = Mockito.mock(InformerManager.class);
        Kafka kafka = new KafkaBuilder(this.kafkaCluster.kafkaFrom(mk, null))
                .editMetadata()
                    .addToAnnotations(StrimziManager.STRIMZI_PAUSE_RECONCILE_ANNOTATION, "true")
                .endMetadata()
                .withNewStatus()
                    .withConditions(new ConditionBuilder().withType("ReconciliationPaused").withStatus("True").build())
                .endStatus()
                .build();

        Mockito.when(informer.getLocalKafka(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(kafka);
        QuarkusMock.installMockForType(informer, InformerManager.class);

        Resource<Deployment> kafkaExporterDeployment = client.apps().deployments().inNamespace(ns).withName(name + "-kafka-exporter");
        Resource<StatefulSet> kafkaStatefulSet = client.apps().statefulSets().inNamespace(ns).withName(KafkaResources.kafkaStatefulSetName(name));
        Resource<StrimziPodSet> kafkaPodSet = client.resources(StrimziPodSet.class).inNamespace(ns).withName(KafkaResources.kafkaStatefulSetName(name));
        Resource<StatefulSet> zkStatefulSet = client.apps().statefulSets().inNamespace(ns).withName(KafkaResources.zookeeperStatefulSetName(name));
        Resource<StrimziPodSet> zkPodSet = client.resources(StrimziPodSet.class).inNamespace(ns).withName(KafkaResources.zookeeperStatefulSetName(name));
        Resource<Route> bootstrapRoute = client.resources(Route.class).inNamespace(ns).withName(name + "-kafka-bootstrap");
        Resource<Route> broker0Route = client.resources(Route.class).inNamespace(ns).withName(name + "-kafka-0");

        Mockito.when(informer.getRoutesInNamespace(ns))
            .thenReturn(Stream.of(bootstrapRoute, broker0Route).map(Resource::get));

        kafkaExporterDeployment.create(new DeploymentBuilder()
                .withNewMetadata()
                    .withName(name + "-kafka-exporter")
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build());

        kafkaStatefulSet.create(createStatefulSet(KafkaResources.kafkaStatefulSetName(name)));
        kafkaPodSet.create(createStrimziPodSet(KafkaResources.kafkaStatefulSetName(name)));
        zkStatefulSet.create(createStatefulSet(KafkaResources.zookeeperStatefulSetName(name)));
        zkPodSet.create(createStrimziPodSet(KafkaResources.zookeeperStatefulSetName(name)));
        bootstrapRoute.create(new RouteBuilder()
                .withNewMetadata()
                    .withName(name + "-kafka-bootstrap")
                    .withLabels(Map.of(OperandUtils.MANAGED_BY_LABEL, OperandUtils.STRIMZI_OPERATOR_NAME))
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build());
        broker0Route.create(new RouteBuilder()
                .withNewMetadata()
                    .withName(name + "-kafka-0")
                    .withLabels(Map.of(OperandUtils.MANAGED_BY_LABEL, OperandUtils.STRIMZI_OPERATOR_NAME))
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build());

        mk.getMetadata().setLabels(Map.of(ManagedKafka.SUSPENDED_INSTANCE, "true"));

        Stream.of(kafkaExporterDeployment, kafkaStatefulSet, kafkaPodSet, zkStatefulSet, zkPodSet)
            .map(Resource::get)
            .forEach(Assertions::assertNotNull);

        Stream.of(bootstrapRoute, broker0Route)
            .map(Resource::get)
            .forEach(route -> {
                assertNotNull(route);
                assertTrue(route.getMetadata().getAnnotations() == null || route.getMetadata().getAnnotations().isEmpty());
            });

        kafkaCluster.createOrUpdate(mk);

        Stream.of(kafkaExporterDeployment, kafkaStatefulSet, kafkaPodSet, zkStatefulSet, zkPodSet)
            .map(Resource::get)
            .forEach(Assertions::assertNull);

        Map<String, String> rateLimitAnnotations = OperandUtils.buildRateLimitAnnotations(5, 5);

        Stream.of(bootstrapRoute, broker0Route)
            .map(Resource::get)
            .forEach(route -> {
                assertNotNull(route);
                assertNotNull(route.getMetadata().getAnnotations());
                assertTrue(rateLimitAnnotations.entrySet()
                        .stream()
                        .allMatch(a -> Objects.equals(a.getValue(), route.getMetadata().getAnnotations().get(a.getKey()))));
            });
    }

    StatefulSet createStatefulSet(String name) {
        return new StatefulSetBuilder()
                .withNewMetadata()
                    .withName(name)
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();
    }

    StrimziPodSet createStrimziPodSet(String name) {
        return new StrimziPodSetBuilder()
                .withNewMetadata()
                    .withName(name)
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();
    }
}
