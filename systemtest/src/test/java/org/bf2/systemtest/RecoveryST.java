package org.bf2.systemtest;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RecoveryST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(RecoveryST.class);

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
    void testDeleteDeployedResources(ExtensionContext extensionContext) {
        String mkAppName = "mk-resource-recovery";
        String testNamespace = "mk-test-resources-recovery";

        var kafkacli = kube.client().customResources(kafkaCrdContext, Kafka.class, KafkaList.class);

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(testNamespace).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(testNamespace, mkAppName);

        resourceManager.createResource(extensionContext, mk);

        LOGGER.info("Delete resources in namespace {}", testNamespace);
        kube.client().apps().deployments().inNamespace(testNamespace).withLabel("app.kubernetes.io/managed-by", "kas-fleetshard-operator").delete();
        kafkacli.inNamespace(testNamespace).withLabel("app.kubernetes.io/managed-by", "kas-fleetshard-operator").delete();

        TestUtils.waitFor("Managed kafka status is installing", 1_000, 60_000, () -> {
            ManagedKafka m = ManagedKafkaResourceType.getOperation().inNamespace(testNamespace).withName(mkAppName).get();
            return Objects.requireNonNull(
                    ManagedKafkaResourceType.getCondition(m.getStatus().getConditions(), ManagedKafkaCondition.Type.Installing)).getStatus().equals("True");
        });

        TestUtils.waitFor("Managed kafka status is again ready", 1_000, 800_000, () -> {
            ManagedKafka m = ManagedKafkaResourceType.getOperation().inNamespace(testNamespace).withName(mkAppName).get();
            return Objects.requireNonNull(
                    ManagedKafkaResourceType.getCondition(m.getStatus().getConditions(), ManagedKafkaCondition.Type.Ready)).getStatus().equals("True");
        });

        assertNotNull(ManagedKafkaResourceType.getOperation().inNamespace(testNamespace).withName(mkAppName).get());
        assertNotNull(kafkacli.inNamespace(testNamespace).withName(mkAppName).get());
        assertTrue(kube.client().pods().inNamespace(testNamespace).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals("Running", ManagedKafkaResourceType.getAdminApiPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());
    }
}
