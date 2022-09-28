package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.common.OperandUtils;
import org.bf2.operator.MockProfile;
import org.bf2.operator.operands.OperandReadiness;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.resources.v1alpha1.ProfileBuilder;
import org.bf2.operator.resources.v1alpha1.ProfileCapacity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@TestProfile(MockProfile.class)
@QuarkusTest
class CapacityManagerTest {

    @Inject
    KubernetesClient client;

    @Inject
    CapacityManager capacityManager;

    @Inject
    InformerManager informerManager;

    @AfterEach
    void cleanUp() {
        client.configMaps().withName(CapacityManager.FLEETSHARD_RESOURCES).delete();
        client.resources(ManagedKafka.class).inAnyNamespace().delete();
        client.resources(Kafka.class).inAnyNamespace().delete();
        client.resources(ManagedKafkaAgent.class).inAnyNamespace().delete();
    }

    @Test
    void testInitialResourceTracking() {
        ManagedKafkaAgent dummyInstance = ManagedKafkaAgentResourceClient.getDummyInstance();
        dummyInstance.getMetadata().setNamespace(client.getNamespace());
        dummyInstance.getSpec()
                .setCapacity(Map.of("standard", new ProfileBuilder().withMaxNodes(30).build(), "developer",
                        new ProfileBuilder().withMaxNodes(30).build()));

        // add a couple of managedkafkas / kafkas
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");
        client.resource(mk).createOrReplace();
        ManagedKafka mk2 = ManagedKafka.getDummyInstance(2);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");
        client.resource(mk2).createOrReplace();

        InformerManager mockInformerManager = Mockito.mock(InformerManager.class);
        QuarkusMock.installMockForType(mockInformerManager, InformerManager.class);

        Kafka kafka = new KafkaBuilder().withNewMetadata()
                .withName(mk.getMetadata().getName())
                .withNamespace(mk.getMetadata().getNamespace())
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .build();
        Mockito.when(mockInformerManager.getLocalKafka(mk.getMetadata().getName(), mk.getMetadata().getNamespace()))
                .thenReturn(kafka);

        // make sure there's a single entry - mk2 has no kafka
        ConfigMap resourceMap = capacityManager.getOrCreateResourceConfigMap(dummyInstance);
        client.resource(resourceMap).delete();
        assertEquals(
                Map.of("standard", "1", CapacityManager.getManagedKafkaKey(mk),
                        "{\"profile\":\"standard\",\"units\":1}"),
                resourceMap.getData());

        Kafka kafka2 = new KafkaBuilder().withNewMetadata()
                .withName(mk2.getMetadata().getName())
                .withNamespace(mk2.getMetadata().getNamespace())
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .build();
        Mockito.when(mockInformerManager.getLocalKafka(mk2.getMetadata().getName(), mk2.getMetadata().getNamespace()))
                .thenReturn(kafka2);

        // both are now expected
        resourceMap = capacityManager.getOrCreateResourceConfigMap(dummyInstance);
        client.resource(resourceMap).delete();
        assertEquals(Map.of("standard", "2", CapacityManager.getManagedKafkaKey(mk),
                "{\"profile\":\"standard\",\"units\":1}", CapacityManager.getManagedKafkaKey(mk2),
                "{\"profile\":\"standard\",\"units\":1}"), resourceMap.getData());

        // a deleted resource shouldn't count
        mk.getSpec().setDeleted(true);
        client.resource(mk).createOrReplace();
        resourceMap = capacityManager.getOrCreateResourceConfigMap(dummyInstance);
        client.resource(resourceMap).delete();
        assertEquals(
                Map.of("standard", "1", CapacityManager.getManagedKafkaKey(mk2),
                        "{\"profile\":\"standard\",\"units\":1}"),
                resourceMap.getData());
    }

    @Test
    void checkForOrphans() {
        ManagedKafkaAgent dummyInstance = ManagedKafkaAgentResourceClient.getDummyInstance();
        dummyInstance.getMetadata().setNamespace(client.getNamespace());
        dummyInstance.getSpec()
                .setCapacity(Map.of("standard", new ProfileBuilder().withMaxNodes(30).build(), "developer",
                        new ProfileBuilder().withMaxNodes(30).build()));

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        InformerManager mockInformerManager = Mockito.mock(InformerManager.class);
        QuarkusMock.installMockForType(mockInformerManager, InformerManager.class);

        // make sure it works over empty data
        ConfigMap resourceMap = capacityManager.getOrCreateResourceConfigMap(dummyInstance);
        capacityManager.checkForOrphans(resourceMap);

        client.resource(resourceMap).delete();

        client.resource(mk).createOrReplace();
        Kafka kafka = new KafkaBuilder().withNewMetadata()
                .withName(mk.getMetadata().getName())
                .withNamespace(mk.getMetadata().getNamespace())
                .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .build();
        Mockito.when(mockInformerManager.getLocalKafka(mk.getMetadata().getName(), mk.getMetadata().getNamespace()))
                .thenReturn(kafka);

        resourceMap = capacityManager.getOrCreateResourceConfigMap(dummyInstance);

        // make sure there's a single entry
        assertEquals(
                Map.of("standard", "1", CapacityManager.getManagedKafkaKey(mk),
                        "{\"profile\":\"standard\",\"units\":1}"),
                resourceMap.getData());

        client.resource(mk).delete();

        resourceMap = capacityManager.checkForOrphans(resourceMap);

        // should remove mk-1
        assertEquals(Map.of("standard", "0"), resourceMap.getData());
    }

    @Test
    void testBuildDefaultCapacity() {
        ManagedKafkaAgent dummyInstance = ManagedKafkaAgentResourceClient.getDummyInstance();
        dummyInstance.getMetadata().setNamespace(client.getNamespace());
        dummyInstance.getSpec()
                .setCapacity(Map.of("developer",
                        new ProfileBuilder().withMaxNodes(30).build()));

        // shouldn't be any developer entries, so max should be the remaining
        ProfileCapacity capacity = capacityManager.buildCapacity(dummyInstance).get("developer");
        assertEquals(300, capacity.getMaxUnits());
        assertEquals(300, capacity.getRemainingUnits());
    }

    @Test
    void testReserved() {
        ManagedKafkaAgent dummyInstance = ManagedKafkaAgentResourceClient.getDummyInstance();
        dummyInstance.getMetadata().setNamespace(client.getNamespace());
        dummyInstance.getSpec()
                .setCapacity(Map.of("standard",
                        new ProfileBuilder().withMaxNodes(30).build()));
        client.resource(dummyInstance).createOrReplace();

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk = new ManagedKafkaBuilder().editMetadata()
                .addToLabels(ManagedKafka.DEPLOYMENT_TYPE, ManagedKafka.RESERVED_DEPLOYMENT_TYPE)
                .endMetadata()
                .build();

        Optional<OperandReadiness> result = capacityManager.claimResources(mk, "standard", dummyInstance);
        // no validation error, and the map should still not exist
        assertTrue(result.isEmpty());
        assertNull(client.configMaps().withName(CapacityManager.FLEETSHARD_RESOURCES).get());

        // add a real instance
        client.configMaps()
                .create(new ConfigMapBuilder().withNewMetadata()
                        .withName(CapacityManager.FLEETSHARD_RESOURCES)
                        .endMetadata()
                        .withData(Map.of("standard", "1", CapacityManager.getManagedKafkaKey(mk),
                                "{\"profile\":\"standard\",\"units\":1}"))
                        .build());

        // should fail flipping real instance to reserved
        OperandReadiness readiness = capacityManager.validateResources(mk).get();
        assertEquals(Status.False, readiness.getStatus());
    }

}
