package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.systemtest.framework.ParallelTest;
import org.bf2.systemtest.framework.TestTags;
import org.bf2.systemtest.framework.TimeoutBudget;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.time.Duration;

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
        FleetShardOperatorManager.deleteFleetShard(kube);
        StrimziOperatorManager.uninstallStrimziClusterWideResources(kube);
    }

    @Tag(TestTags.SMOKE)
    @ParallelTest
    void testDeployManagedKafka(ExtensionContext extensionContext) {
        String mkAppName = "mk-test-create";

        var kafkacli = kube.client().customResources(kafkaCrdContext, Kafka.class, KafkaList.class);

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);

        resourceManager.createResource(extensionContext, mk);

        assertNotNull(ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get());
        assertNotNull(kafkacli.inNamespace(mkAppName).withName(mkAppName).get());
        assertTrue(kube.client().pods().inNamespace(mkAppName).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals("Running", ManagedKafkaResourceType.getAdminApiPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        if (!kube.isGenericKubernetes()) {
            assertEquals(1, ManagedKafkaResourceType.getKafkaExporterPods(mk).size());
        }
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());
    }

    @ParallelTest
    void testCreateDeleteCreateSameManagedKafka(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-create-delete";

        var kafkacli = kube.client().customResources(kafkaCrdContext, Kafka.class, KafkaList.class);

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);
        resourceManager.createResource(extensionContext, mk);

        LOGGER.info("Remove managedKafka");
        resourceManager.deleteResource(mk);

        LOGGER.info("Create managedkafka again");
        //added more timeout because of strimzi reconcile interval
        resourceManager.createResource(extensionContext, TimeoutBudget.ofDuration(Duration.ofMinutes(15)), mk);

        assertTrue(ManagedKafkaResourceType.getOperation().inAnyNamespace().list().getItems().size() > 0);

        assertNotNull(ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get());
        assertNotNull(kafkacli.inNamespace(mkAppName).withName(mkAppName).get());
        assertTrue(kube.client().pods().inNamespace(mkAppName).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals("Running", ManagedKafkaResourceType.getAdminApiPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());
    }
}
