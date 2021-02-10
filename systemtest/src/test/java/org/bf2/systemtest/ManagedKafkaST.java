package org.bf2.systemtest;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ManagedKafkaST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaST.class);

    @BeforeAll
    void deployStrimzi() throws Exception {
        StrimziOperatorManager.installStrimzi(kube);
        FleetShardOperatorManager.deployFleetShardOperator(kube);
    }

    @AfterAll
    void clean() throws InterruptedException {
        FleetShardOperatorManager.deleteFleetShardOperator(kube);
        StrimziOperatorManager.uninstallStrimziClusterWideResources(kube);
    }

    @Test
    void testDeployManagedKafka(ExtensionContext extensionContext) {
        String mkAppName = "mk-create";
        String testNamespace = "mk-test-create-check";

        var kafkacli = kube.client().customResources(Kafka.class, KafkaList.class);

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(testNamespace).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(testNamespace, mkAppName);

        resourceManager.createResource(extensionContext, mk);

        assertNotNull(ManagedKafkaResourceType.getOperation().inNamespace(testNamespace).withName(mkAppName).get());
        assertNotNull(kafkacli.inNamespace(testNamespace).withName(mkAppName).get());
        assertTrue(kube.client().pods().inNamespace(testNamespace).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals("Running", ManagedKafkaResourceType.getAdminApiPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());
    }

    @Test
    void testCreateDeleteCreateSameManagedKafka(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-create-delete";
        String testNamespace = "mk-test-create-delete";

        var kafkacli = kube.client().customResources(Kafka.class, KafkaList.class);

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(testNamespace).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(testNamespace, mkAppName);
        resourceManager.createResource(extensionContext, mk);

        LOGGER.info("Remove managedKafka");
        resourceManager.deleteResource(mk);

        LOGGER.info("Create managedkafka again");
        resourceManager.createResource(extensionContext, mk);

        assertTrue(ManagedKafkaResourceType.getOperation().inAnyNamespace().list().getItems().size() > 0);

        assertNotNull(ManagedKafkaResourceType.getOperation().inNamespace(testNamespace).withName(mkAppName).get());
        assertNotNull(kafkacli.inNamespace(testNamespace).withName(mkAppName).get());
        assertTrue(kube.client().pods().inNamespace(testNamespace).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals("Running", ManagedKafkaResourceType.getAdminApiPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());
    }
}
