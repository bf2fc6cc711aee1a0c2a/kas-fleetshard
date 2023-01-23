package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.systemtest.api.sync.SyncApiClient;
import org.bf2.systemtest.framework.AssertUtils;
import org.bf2.systemtest.framework.SequentialTest;
import org.bf2.systemtest.framework.SystemTestEnvironment;
import org.bf2.systemtest.framework.TestTags;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag(TestTags.UPGRADE)
public class UpgradeST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(UpgradeST.class);
    private StrimziOperatorManager strimziOperatorManagerOld;
    private String latestStrimziVersion;

    @BeforeAll
    void deploy() throws Exception {
        strimziOperatorManagerOld = new StrimziOperatorManager(
                StrimziOperatorManager.getPreviousUpstreamStrimziVersion(SystemTestEnvironment.STRIMZI_VERSION));

        strimziOperatorManagerOld.installStrimzi(kube).join();
        latestStrimziVersion = SyncApiClient.getLatestStrimziVersion(syncEndpoint);
    }

    @AfterAll
    void clean() {
        strimziOperatorManagerOld.uninstallStrimziClusterWideResources(kube).join();
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
            return Objects.equals(latestStrimziVersion, actualStrimziVersion(mkAppName));
        });

        //delete mk using api
        res = SyncApiClient.deleteManagedKafka(mk.getId(), syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        ManagedKafkaResourceType.isDeleted(mk);

        LOGGER.info("ManagedKafka {} deleted", mkAppName);
    }

    @SequentialTest
    void testUpgradeStrimziVersionWhileSuspended(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "mk-test-suspended-upgrade";
        String startVersion = SyncApiClient.getPreviousStrimziVersion(syncEndpoint);
        String kafkaVersion = SyncApiClient.getLatestKafkaVersion(syncEndpoint, startVersion);

        ManagedKafka mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName, keycloak, startVersion, kafkaVersion);
        String id = mk.getId();

        //Create mk using api
        resourceManager.addResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(mkAppName).endMetadata().build());
        resourceManager.addResource(extensionContext, mk);

        HttpResponse<String> res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        resourceManager.waitResourceCondition(mk, Objects::nonNull);
        mk = resourceManager.waitUntilReady(mk, 300_000);

        LOGGER.info("ManagedKafka {} created", mkAppName);

        // wait for the sync to be up-to-date
        TestUtils.waitFor("Managed kafka status sync", 1_000, 30_000, () -> {
            try {
                String statusBody = SyncApiClient.getManagedKafkaStatus(id, syncEndpoint).body();
                if (statusBody.isEmpty()) {
                    return false;
                }
                ManagedKafkaStatus apiStatus = Serialization.jsonMapper().readValue(statusBody, ManagedKafkaStatus.class);
                return ManagedKafkaResourceType.hasConditionStatus(apiStatus, ManagedKafkaCondition.Type.Ready,
                        ManagedKafkaCondition.Status.True);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        LOGGER.info("Suspending ManagedKafka");
        mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName, keycloak, startVersion, kafkaVersion);
        mk.setId(id);
        mk.getMetadata().setLabels(Map.of(ManagedKafka.SUSPENDED_INSTANCE, "true"));
        resourceManager.addResource(extensionContext, mk);
        res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        TestUtils.waitFor("ManagedKafka suspended", 1_000, Duration.ofMinutes(5).toMillis(), () -> managedKafkaSuspended(mkAppName));

        LOGGER.info("Upgrade to {}", latestStrimziVersion);
        mk = ManagedKafkaResourceType.getDefault(mkAppName, mkAppName, keycloak, latestStrimziVersion, kafkaVersion);
        mk.setId(id);
        mk.getMetadata().setLabels(Map.of(ManagedKafka.SUSPENDED_INSTANCE, "true"));
        resourceManager.addResource(extensionContext, mk);
        res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());
        TestUtils.waitFor("ManagedKafka upgraded", 1_000, Duration.ofMinutes(10).toMillis(), () ->
            Objects.equals(latestStrimziVersion, actualStrimziVersion(mkAppName))
                && managedKafkaSuspended(mkAppName));

        LOGGER.info("Resuming ManagedKafka from suspended state");
        mk.getMetadata().setLabels(Map.of(ManagedKafka.SUSPENDED_INSTANCE, "false"));
        res = SyncApiClient.createManagedKafka(mk, syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());
        mk = resourceManager.waitUntilReady(mk, 300_000);

        assertEquals(latestStrimziVersion, ManagedKafkaResourceType.getOperation().inNamespace(mkAppName)
                .withName(mkAppName).get().getStatus().getVersions().getStrimzi());

        // wait for the sync to be up-to-date
        TestUtils.waitFor("Managed kafka status sync", 1_000, 30_000, () -> {
            try {
                String statusBody = SyncApiClient.getManagedKafkaStatus(id, syncEndpoint).body();
                if (statusBody.isEmpty()) {
                    return false;
                }
                ManagedKafkaStatus apiStatus = Serialization.jsonMapper().readValue(statusBody, ManagedKafkaStatus.class);
                return ManagedKafkaResourceType.hasConditionStatus(apiStatus, ManagedKafkaCondition.Type.Ready,
                        ManagedKafkaCondition.Status.True);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        //Get status and compare with CR status
        ManagedKafkaStatus apiStatus = Serialization.jsonMapper()
                .readValue(SyncApiClient.getManagedKafkaStatus(mk.getId(), syncEndpoint).body(), ManagedKafkaStatus.class);
        ManagedKafka managedKafka = ManagedKafkaResourceType.getOperation().inNamespace(mkAppName).withName(mkAppName).get();

        AssertUtils.assertManagedKafkaStatus(managedKafka, apiStatus);

        //Get agent status
        ManagedKafkaAgentStatus managedKafkaAgentStatus = Serialization.jsonMapper()
                .readValue(SyncApiClient.getManagedKafkaAgentStatus(syncEndpoint).body(), ManagedKafkaAgentStatus.class);

        AssertUtils.assertManagedKafkaAgentStatus(managedKafkaAgentStatus);

        //Check if managed kafka deployed all components
        AssertUtils.assertManagedKafka(mk);

        //delete mk using api
        res = SyncApiClient.deleteManagedKafka(mk.getId(), syncEndpoint);
        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, res.statusCode());

        ManagedKafkaResourceType.isDeleted(mk);

        LOGGER.info("ManagedKafka {} deleted", mkAppName);
    }

    String actualStrimziVersion(String mkAppName) {
        return ManagedKafkaResourceType.getOperation()
                .inNamespace(mkAppName)
                .withName(mkAppName)
                .get()
                .getStatus()
                .getVersions()
                .getStrimzi();
    }

    boolean managedKafkaSuspended(String mkAppName) {
        ManagedKafka remote = ManagedKafkaResourceType.getOperation()
                .inNamespace(mkAppName)
                .withName(mkAppName)
                .get();

        var readyCondition = ManagedKafkaResourceType.getCondition(remote.getStatus(), ManagedKafkaCondition.Type.Ready);

        if (readyCondition.map(c -> !"False".equals(c.getStatus()) || !"Suspended".equals(c.getReason())).orElse(true)) {
            return false;
        }

        List<Pod> pods = kube.client().pods().inNamespace(mkAppName).list().getItems();

        if (!pods.isEmpty()) {
            return false;
        }

        String pauseReason = kube.client().resources(Kafka.class)
            .inNamespace(mkAppName)
            .withName(mkAppName)
            .get()
            .getMetadata()
            .getAnnotations()
            .get(ManagedKafkaKeys.Annotations.STRIMZI_PAUSE_REASON);

        if (pauseReason != null && !pauseReason.isBlank()) {
            return false;
        }

        return true;
    }
}
