package org.bf2.performance;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.systemtest.api.sync.SyncApiClient;
import org.bf2.systemtest.framework.ParallelTest;
import org.bf2.systemtest.framework.SequentialTest;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.integration.AbstractST;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for integration tests against the operator and strimzi - no other components are required to be installed
 *
 * Intended to be run against prod, not dev instances
 *
 * TODO: this can be moved into system test if the olm install is moved there, which would replace the strimzi github/community install method
 */
public class OperatorST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(OperatorST.class);
    private OlmBasedStrimziOperatorManager strimziOperatorManager;
    private List<String> strimziVersions;
    private String latestStrimziVersion;

    @BeforeAll
    void deploy() throws Exception {
        strimziOperatorManager = new OlmBasedStrimziOperatorManager(kube, StrimziOperatorManager.OPERATOR_NS);

        CompletableFuture.allOf(
                strimziOperatorManager.deployStrimziOperator(),
                FleetShardOperatorManager.deployFleetShardOperator(kube)).join();

        // since sync is not installed, manually create the agent resource
        var agentResource = kube.client()
                .resource(new ManagedKafkaAgentBuilder().withNewMetadata()
                        .withName(ManagedKafkaAgentResourceClient.RESOURCE_NAME)
                        .withNamespace(FleetShardOperatorManager.OPERATOR_NS)
                        .endMetadata()
                        .withSpec(new ManagedKafkaAgentSpecBuilder()
                                .withNewObservability()
                                .withAccessToken("")
                                .withChannel("")
                                .withRepository("")
                                .withTag("")
                                .endObservability()
                                .build())
                        .build());
        agentResource.createOrReplace();

        // the operator will update the status after a while
        strimziVersions = SyncApiClient.getSortedAvailableStrimziVersions(() -> agentResource.fromServer().get().getStatus()).collect(Collectors.toList());
        assertTrue(strimziVersions.size() > 1);

        latestStrimziVersion = strimziVersions.get(strimziVersions.size() - 1);
    }

    @AfterAll
    void clean() {
        CompletableFuture.allOf(
                FleetShardOperatorManager.deleteFleetShard(kube),
                strimziOperatorManager.deleteStrimziOperator()).join();
    }

    @SequentialTest
    void testUpgradeStrimziVersion(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-upgrade";
        LOGGER.info("Create namespace");
        resourceManager.addResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        String startVersion = strimziVersions.get(strimziVersions.size() - 2);

        LOGGER.info("Create managedkafka with version {}", startVersion);
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName, null, startVersion);
        mk = resourceManager.createResource(extensionContext, mk);

        Resource<ManagedKafka> mkResource = kube.client()
                .resources(ManagedKafka.class)
                .inNamespace(mk.getMetadata().getNamespace())
                .withName(mk.getMetadata().getName());

        LOGGER.info("Upgrading managedkafka to version {}", latestStrimziVersion);
        mkResource.edit(r -> {
            r.getSpec().getVersions().setStrimzi(latestStrimziVersion);
            return r;
        });

        mkResource.waitUntilCondition(m -> {
            String reason = ManagedKafkaResourceType.getCondition(m.getStatus(), ManagedKafkaCondition.Type.Ready)
                    .get()
                    .getReason();
            return ManagedKafkaCondition.Reason.StrimziUpdating.name().equals(reason);
        }, 5, TimeUnit.MINUTES);

        mkResource.waitUntilCondition(
                m -> ManagedKafkaResourceType.getCondition(m.getStatus(), ManagedKafkaCondition.Type.Ready)
                        .get()
                        .getReason() == null && latestStrimziVersion.equals(m.getStatus().getVersions().getStrimzi()),
                10, TimeUnit.MINUTES);
    }

    @ParallelTest
    void testResizeAndCapacity(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-resize-capacity";

        LOGGER.info("Create namespace");
        resourceManager.addResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        LOGGER.info("Create managedkafka");
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName, null, latestStrimziVersion);
        Quantity quantity = Quantity.parse("100Gi");
        // for values below 270Gi, the logic will report a slightly larger values
        Quantity reportedQuantity = Quantity.parse("103Gi");
        mk.getSpec().getCapacity().setMaxDataRetentionSize(quantity);
        mk = resourceManager.createResource(extensionContext, mk);

        Resource<ManagedKafka> mkResource = kube.client()
                .resources(ManagedKafka.class)
                .inNamespace(mk.getMetadata().getNamespace())
                .withName(mk.getMetadata().getName());

        assertEquals(reportedQuantity, mk.getStatus().getCapacity().getMaxDataRetentionSize());

        LOGGER.info("Trying to shrink");
        mk.getSpec().getCapacity().setMaxDataRetentionSize(Quantity.parse("50Gi"));
        mk = mkResource.createOrReplace(mk);

        String currentVersion = mk.getMetadata().getResourceVersion();
        // wait until the status is updated
        mk = mkResource.waitUntilCondition(m -> !Objects.equals(currentVersion, m.getMetadata().getResourceVersion()), 2, TimeUnit.MINUTES);

        // should be the same, as we can't resize down
        assertEquals(reportedQuantity, mk.getStatus().getCapacity().getMaxDataRetentionSize());

        LOGGER.info("Trying to grow");
        mk.getSpec().getCapacity().setMaxDataRetentionSize(Quantity.parse("200Gi"));
        mk = mkResource.createOrReplace(mk);

        // should grow - again the size is a little off
        Quantity endReportedQuantity = Quantity.parse("202Gi");
        mk = mkResource.waitUntilCondition(m -> endReportedQuantity.equals(m.getStatus().getCapacity().getMaxDataRetentionSize()), 5, TimeUnit.MINUTES);
    }
}
