package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.systemtest.api.sync.SyncApiClient;
import org.bf2.systemtest.framework.AssertUtils;
import org.bf2.systemtest.framework.KeycloakInstance;
import org.bf2.systemtest.framework.SequentialTest;
import org.bf2.systemtest.framework.SystemTestEnvironment;
import org.bf2.systemtest.framework.TestTags;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.KeycloakOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag(TestTags.UPGRADE)
public class UpgradeST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(UpgradeST.class);
    private String syncEndpoint;
    private StrimziOperatorManager strimziOperatorManagerOld;
    private StrimziOperatorManager strimziOperatorManagerNew;
    private KeycloakInstance keycloak;
    private String latestStrimziVersion;

    @BeforeAll
    void deploy() throws Exception {
        strimziOperatorManagerOld = new StrimziOperatorManager(
                StrimziOperatorManager.getPreviousUpstreamStrimziVersion(SystemTestEnvironment.STRIMZI_VERSION));
        strimziOperatorManagerNew = new StrimziOperatorManager(SystemTestEnvironment.STRIMZI_VERSION);
        CompletableFuture.allOf(
                KeycloakOperatorManager.installKeycloak(kube),
                strimziOperatorManagerNew.installStrimzi(kube),
                strimziOperatorManagerOld.installStrimzi(kube),
                FleetShardOperatorManager.deployFleetShardOperator(kube),
                FleetShardOperatorManager.deployFleetShardSync(kube)).join();

        keycloak = SystemTestEnvironment.INSTALL_KEYCLOAK ? new KeycloakInstance(KeycloakOperatorManager.OPERATOR_NS) : null;
        syncEndpoint = FleetShardOperatorManager.createEndpoint(kube);
        latestStrimziVersion = SyncApiClient.getLatestStrimziVersion(syncEndpoint);
        LOGGER.info("Endpoint address {}", syncEndpoint);
    }

    @AfterAll
    void clean() {
        CompletableFuture.allOf(
                KeycloakOperatorManager.uninstallKeycloak(kube),
                FleetShardOperatorManager.deleteFleetShard(kube),
                strimziOperatorManagerOld.uninstallStrimziClusterWideResources(kube),
                strimziOperatorManagerNew.uninstallStrimziClusterWideResources(kube)).join();
    }

    @SequentialTest
    void testUpgradeStrimziVersion(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-upgrade";

        LOGGER.info("Create namespace");
        resourceManager.addResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());

        String startVersion = SyncApiClient.getPreviousStrimziVersion(syncEndpoint);
        String kafkaVersion = SyncApiClient.getLatestKafkaVersion(syncEndpoint, startVersion);

        LOGGER.info("Create managedkafka with version {}", startVersion);
        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName, keycloak, startVersion, kafkaVersion);
        String id = mk.getId();
        resourceManager.addResource(extensionContext, mk);

        HttpResponse<String> res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        resourceManager.waitResourceCondition(mk, Objects::nonNull);
        mk = resourceManager.waitUntilReady(mk, 300_000);
        AssertUtils.assertManagedKafka(mk);

        LOGGER.info("Upgrade to {}", latestStrimziVersion);
        mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName, keycloak, latestStrimziVersion, kafkaVersion);
        mk.setId(id);
        resourceManager.addResource(extensionContext, mk);

        res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        if (!ManagedKafkaResourceType.isDevKafka(mk)) {
            resourceManager.waitResourceCondition(mk, m -> {
                        String reason = ManagedKafkaResourceType.getCondition(m.getStatus(), ManagedKafkaCondition.Type.Ready).get().getReason();
                        if (reason != null) {
                            return reason.equals(ManagedKafkaCondition.Reason.StrimziUpdating.toString());
                        }
                        return false;
                    },
                    TimeUnit.MINUTES.toMillis(5));
            resourceManager.waitResourceCondition(mk, m ->
                            ManagedKafkaResourceType.getCondition(m.getStatus(), ManagedKafkaCondition.Type.Ready).get()
                                    .getReason() == null,
                    TimeUnit.MINUTES.toMillis(10));
        }

        TestUtils.waitFor("MK is upgraded", TimeUnit.SECONDS.toMillis(20), TimeUnit.MINUTES.toMillis(10), () -> {
            try {
                assertEquals(latestStrimziVersion, ManagedKafkaResourceType.getOperation().inNamespace(mkAppName)
                        .withName(mkAppName).get().getStatus().getVersions().getStrimzi());
                return true;
            } catch (AssertionError err) {
                return false;
            }
        });
    }
}
