package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.systemtest.framework.AssertUtils;
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

public class ManagedKafkaST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaST.class);

    @BeforeAll
    void deploy() throws Exception {
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

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext,
                new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);

        resourceManager.createResource(extensionContext, mk);

        AssertUtils.assertManagedKafka(mk);
    }

    @ParallelTest
    void testCreateDeleteCreateSameManagedKafka(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-create-delete";

        LOGGER.info("Create namespace");
        resourceManager.createResource(extensionContext,
                new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName);
        resourceManager.createResource(extensionContext, mk);

        AssertUtils.assertManagedKafka(mk);

        LOGGER.info("Remove managedKafka");
        resourceManager.deleteResource(mk);

        LOGGER.info("Create managedkafka again");
        //added more timeout because of strimzi reconcile interval
        resourceManager.createResource(extensionContext, TimeoutBudget.ofDuration(Duration.ofMinutes(15)), mk);

        AssertUtils.assertManagedKafka(mk);
    }
}
