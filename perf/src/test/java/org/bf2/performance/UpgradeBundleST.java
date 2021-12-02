package org.bf2.performance;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.systemtest.api.sync.SyncApiClient;
import org.bf2.systemtest.framework.AssertUtils;
import org.bf2.systemtest.framework.SequentialTest;
import org.bf2.systemtest.framework.TestTags;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.k8s.KubeClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestTags.UPGRADE)
public class UpgradeBundleST extends TestBase {

    private static final Logger LOGGER = LogManager.getLogger(UpgradeBundleST.class);
    private OlmBasedStrimziOperatorManager strimziOperatorManager;
    private List<String> strimziVersions;
    private KubeClient kube = KubeClient.getInstance();
    String mkAppName = "mk-test-upgrade";

    @BeforeAll
    void deploy() throws Exception {
        strimziOperatorManager = new OlmBasedStrimziOperatorManager(kube, StrimziOperatorManager.OPERATOR_NS);

        CompletableFuture.allOf(
                strimziOperatorManager.deployStrimziOperator(),
                FleetShardOperatorManager.deployFleetShardOperator(kube)).join();

        // since sync is not installed, manually create the agent resource
        var agentResource =
                kube.client()
                        .resources(ManagedKafkaAgent.class)
                        .inNamespace(FleetShardOperatorManager.OPERATOR_NS)
                        .withName(ManagedKafkaAgentResourceClient.RESOURCE_NAME);

        agentResource
                .createOrReplace(new ManagedKafkaAgentBuilder().withNewMetadata()
                        .withName(ManagedKafkaAgentResourceClient.RESOURCE_NAME)
                        .endMetadata()
                        .withSpec(new ManagedKafkaAgentSpecBuilder()
                                .withNewObservability()
                                .withAccessToken("")
                                .withChannel("")
                                .withRepository("")
                                .withTag("")
                                .endObservability().build())
                        .build());

        // the operator will update the status after a while
        strimziVersions = SyncApiClient.getSortedAvailableStrimziVersions(() -> agentResource.get().getStatus()).collect(Collectors.toList());
        assertTrue(strimziVersions.size() > 1);
    }

    @AfterAll
    void clean() {
        CompletableFuture.allOf(
                FleetShardOperatorManager.deleteFleetShard(kube),
                strimziOperatorManager.deleteStrimziOperator()).join();
    }

    @AfterEach
    void after() {
        kube.client().namespaces().withName(mkAppName).delete();
    }

    @SequentialTest
    void testUpgradeStrimziVersion() throws Exception {
        LOGGER.info("Create namespace");
        kube.client().resource(new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build()).createOrReplace();

        String startVersion = strimziVersions.get(strimziVersions.size() - 2);

        LOGGER.info("Create managedkafka with version {}", startVersion);
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName, null, startVersion);
        mk = kube.client().resource(mk).createOrReplace();

        Resource<ManagedKafka> mkResource = kube.client()
                .resources(ManagedKafka.class)
                .inNamespace(mk.getMetadata().getNamespace())
                .withName(mk.getMetadata().getName());

        mkResource
                .waitUntilCondition(m -> Objects.nonNull(m)
                        && ManagedKafkaResourceType.getCondition(m.getStatus(), ManagedKafkaCondition.Type.Ready)
                                .filter(c -> ManagedKafkaCondition.Status.True.name().equals(c.getStatus()))
                                .isPresent(),
                        5, TimeUnit.MINUTES);

        AssertUtils.assertManagedKafka(mk);

        String endVersion = strimziVersions.get(strimziVersions.size() - 1);

        LOGGER.info("Upgrading managedkafka to version {}", endVersion);
        mkResource.edit(r -> {
            r.getSpec().getVersions().setStrimzi(endVersion);
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
                        .getReason() == null && endVersion.equals(m.getStatus().getVersions().getStrimzi()),
                10, TimeUnit.MINUTES);
    }
}
