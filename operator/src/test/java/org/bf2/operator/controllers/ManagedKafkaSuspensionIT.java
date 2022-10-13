package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.javaoperatorsdk.operator.Operator;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.ObservabilityManager;
import org.bf2.operator.operands.KafkaInstanceConfigurations;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.ServiceAccountBuilder;
import org.bf2.operator.resources.v1alpha1.StrimziVersionComparator;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatus;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 * Run this test by:
 *
 * <ol>
 * <li>deploy the control and data planes using kas-installer
 * <li>scale the kas-fleetshard operator and sync to zero replicas
 * </ol>
 */
@QuarkusTest
@TestProfile(ManagedKafkaSuspensionIT.Profile.class)
@EnabledIfSystemProperty(named = "mk-integration-test", matches = "true")
class ManagedKafkaSuspensionIT {

    static final String OWNER_PRINCIPAL_NAME = "user-123";
    static final String CANARY_PRINCIPAL_NAME = "canary-123";
    static final String CANARY_PRINCIPAL_KEY = "canary.principal";
    static final String CANARY_SECRET_KEY = "canary.secret";

    public static class Profile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "prod";
        }
        @Override
        public boolean disableGlobalTestResources() {
            return true;
        }
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "agent.status.interval", "5s",
                    "quarkus.kubernetes-client.namespace", "kas-fleetshard-" + UUID.randomUUID().toString(),
                    "managedkafka.image-pull-secrets", "rhoas-image-pull-secret",
                    // Disabling the authorizer so that the canary can function without a real service account
                    "managedkafka.kafka.acl.authorizer-class", "");
        }
    }

    Encoder b64 = Base64.getEncoder();

    @Inject
    KubernetesClient client;

    @Inject
    Operator operator;

    @Inject
    ManagedKafkaResourceClient mkClient;

    @Inject
    ManagedKafkaAgentResourceClient mkaClient;

    @Inject
    Config config;

    @ConfigProperty(name = "managedkafka.kafka.acl.broker-plugins-config-prefix")
    String aclConfigPrefix;

    @ConfigProperty(name = "managedkafka.kafka.acl.suspended")
    String aclDenyAll;

    String operatorNs;
    String kafkaNs;
    List<Namespace> namespaces;

    @BeforeEach
    void setup() {
        namespaces = new ArrayList<>(2);

        operatorNs = config.getValue("quarkus.kubernetes-client.namespace", String.class);

        namespaces.add(client.namespaces().create(new NamespaceBuilder()
                .withNewMetadata()
                    .withName(operatorNs)
                .endMetadata()
                .build()));

        createImagePullSecret();

        kafkaNs = "kafka-" + UUID.randomUUID().toString();

        namespaces.add(client.namespaces().create(new NamespaceBuilder()
                .withNewMetadata()
                    .withName(kafkaNs)
                    .addToLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .build()));

        operator.start();

        ManagedKafkaAgent mka = ManagedKafkaAgentResourceClient.getDummyInstance();
        mka.getMetadata().setNamespace(operatorNs);
        mkaClient.create(mka);

        await().atMost(20, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
            Resource<Secret> observabilityResource = client.secrets()
                    .inNamespace(operatorNs)
                    .withName(ObservabilityManager.OBSERVABILITY_SECRET_NAME);
            Secret observability = observabilityResource.get();

            if (observability != null) {
                observability.getMetadata()
                    .setAnnotations(Map.of(
                            ObservabilityManager.OBSERVABILITY_OPERATOR_STATUS,
                            ObservabilityManager.ACCEPTED));

                observabilityResource.patch(observability);

                return true;
            }

            return false;
        });

        await().atMost(5, TimeUnit.MINUTES).untilAsserted(() -> {
            ManagedKafkaAgent mkaRemote = mkaClient.getByName(operatorNs, ManagedKafkaAgentResourceClient.RESOURCE_NAME);

            String readyStatus = readyStatus(Optional.ofNullable(mkaRemote)
                    .map(ManagedKafkaAgent::getStatus)
                    .map(ManagedKafkaAgentStatus::getConditions));

            assertEquals("True", readyStatus);
        });
    }

    @AfterEach
    void teardown() {
        operator.stop();
        client.namespaces().delete(namespaces);
    }

    @Test
    void testManagedKafkaSuspendAndResume() {
        Object[] suspendedAcls = Arrays.stream(aclDenyAll.split("\n")).map(String::trim).toArray(Object[]::new);
        @SuppressWarnings("unchecked")
        Map.Entry<String, String>[] rateLimitAnnotations = OperandUtils.buildRateLimitAnnotations(5, 5).entrySet().toArray(Map.Entry[]::new);
        String name = "mk1";

        // Initial creation of the instance, verify ACLs
        createManagedKafka(name);

        await().atMost(10, TimeUnit.MINUTES).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            managedKafkaReady(name);
        });

        Kafka kafka = client.resources(Kafka.class).inNamespace(kafkaNs).withName(name).get();
        Map<String, Object> kafkaAcls = getKafkaAcls(kafka.getSpec().getKafka().getConfig());

        assertEquals(5L, getPrincipalAcls(OWNER_PRINCIPAL_NAME, kafkaAcls).size());
        assertEquals(7L, getPrincipalAcls(CANARY_PRINCIPAL_NAME, kafkaAcls).size());
        assertTrue(kafkaAcls.values().stream().noneMatch(Arrays.asList(suspendedAcls)::contains));

        // Suspend the instance and confirm all pods are removed
        ManagedKafka mk = mkClient.getByName(kafkaNs, name);
        mk.getMetadata().getLabels().put(ManagedKafka.SUSPENDED_INSTANCE, "true");
        mkClient.patch(mk);

        await().atMost(10, TimeUnit.MINUTES).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertTrue(client.pods().inNamespace(kafkaNs).list().getItems().isEmpty());
        });

        // Test the resource state now that the cluster has been suspended
        kafka = client.resources(Kafka.class).inNamespace(kafkaNs).withName(name).get();
        kafkaAcls = getKafkaAcls(kafka.getSpec().getKafka().getConfig());

        assertEquals(0L, getPrincipalAcls(OWNER_PRINCIPAL_NAME, kafkaAcls).size());
        assertEquals(7L, getPrincipalAcls(CANARY_PRINCIPAL_NAME, kafkaAcls).size());
        assertThat(kafkaAcls.values(), hasItems(suspendedAcls));

        List<Route> kafkaRoutes = client.resources(Route.class)
                .inNamespace(kafkaNs)
                .list()
                .getItems()
                .stream()
                .filter(r -> r.getMetadata().getName().startsWith(name + "-kafka-"))
                .collect(Collectors.toList());

        assertEquals(kafka.getSpec().getKafka().getReplicas() + 1, kafkaRoutes.size());
        kafkaRoutes.forEach(route ->
                assertThat(route.getMetadata().getAnnotations().entrySet(), hasItems(rateLimitAnnotations)));

        // Resume the instance by removing the annotation
        mk = mkClient.getByName(kafkaNs, name);
        mk.getMetadata().getLabels().remove(ManagedKafka.SUSPENDED_INSTANCE);
        mkClient.patch(mk);

        await().atMost(10, TimeUnit.MINUTES).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            managedKafkaReady(name);
        });

        kafka = client.resources(Kafka.class).inNamespace(kafkaNs).withName(name).get();
        kafkaAcls = getKafkaAcls(kafka.getSpec().getKafka().getConfig());

        assertEquals(5L, getPrincipalAcls(OWNER_PRINCIPAL_NAME, kafkaAcls).size());
        assertEquals(7L, getPrincipalAcls(CANARY_PRINCIPAL_NAME, kafkaAcls).size());
        assertTrue(kafkaAcls.values().stream().noneMatch(Arrays.asList(suspendedAcls)::contains));

        kafkaRoutes = client.resources(Route.class)
                .inNamespace(kafkaNs)
                .list()
                .getItems()
                .stream()
                .filter(r -> r.getMetadata().getName().startsWith(name + "-kafka-"))
                .collect(Collectors.toList());

        assertEquals(kafka.getSpec().getKafka().getReplicas() + 1, kafkaRoutes.size());
        // Require that none of the rate limit annotations are present on the Kafka routes
        assertTrue(kafkaRoutes.stream()
                .map(Route::getMetadata)
                .map(ObjectMeta::getAnnotations)
                .filter(Objects::nonNull)
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .noneMatch(Arrays.asList(rateLimitAnnotations)::contains));
    }

    void createManagedKafka(String name) {
        String endpointTlsCert = null;
        String endpointTlsKey = null;
        String oauthClientId = null;
        String oauthTlsCert = null;
        String oauthClientSecret = null;
        String oauthUserClaim = null;
        String oauthFallbackUserClaim = null;
        String oauthJwksEndpoint = null;
        String oauthTokenEndpoint = null;
        String oauthIssuerEndpoint = null;

        String bootstrapHostDomain = client.resources(IngressController.class)
                .inNamespace("openshift-ingress-operator")
                .withName("default")
                .get()
                .getStatus()
                .getDomain();

        ManagedKafkaAgent mka = mkaClient.getByName(operatorNs, ManagedKafkaAgentResourceClient.RESOURCE_NAME);

        StrimziVersionStatus earliest = mka.getStatus()
            .getStrimzi()
            .stream()
            .sorted(Comparator.comparing(StrimziVersionStatus::getVersion, new StrimziVersionComparator()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No Strimzi versions available in status!"));

        String strimziVersion = earliest.getVersion();
        String kafkaVersion = earliest.getKafkaVersions()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Kafka versions available for Strimzi " + strimziVersion));

        ManagedKafka mk = ManagedKafka.getDefault(name, kafkaNs, bootstrapHostDomain, endpointTlsCert, endpointTlsKey,
                oauthClientId, oauthTlsCert, oauthClientSecret, oauthUserClaim, oauthFallbackUserClaim,
                oauthJwksEndpoint, oauthTokenEndpoint, oauthIssuerEndpoint, strimziVersion, kafkaVersion);

        String masterSecretName = OperandUtils.masterSecretName(mk);

        mk = new ManagedKafkaBuilder(mk)
            .editMetadata()
                .addToLabels(
                        ManagedKafka.PROFILE_TYPE,
                        KafkaInstanceConfigurations.InstanceType.DEVELOPER.getLowerName())
            .endMetadata()
            .editSpec()
                .addToOwners(OWNER_PRINCIPAL_NAME)
                .withServiceAccounts(List.of(new ServiceAccountBuilder()
                    .withName("canary")
                    .withNewPrincipalRef()
                        .withName(masterSecretName)
                        .withKey(CANARY_PRINCIPAL_KEY)
                    .endPrincipalRef()
                    .withNewPasswordRef()
                        .withName(masterSecretName)
                        .withKey(CANARY_SECRET_KEY)
                    .endPasswordRef()
                    .build()))
            .endSpec()
            .build();

        mk = mkClient.create(mk);

        client.secrets()
            .inNamespace(kafkaNs)
            .create(new SecretBuilder()
                .withNewMetadata()
                    .withName(masterSecretName)
                .endMetadata()
                .withData(Map.of(
                        CANARY_PRINCIPAL_KEY, b64.encodeToString(CANARY_PRINCIPAL_NAME.getBytes(StandardCharsets.UTF_8)),
                        CANARY_SECRET_KEY, b64.encodeToString("tw33t".getBytes(StandardCharsets.UTF_8))))
                .build());
    }

    void managedKafkaReady(String name) {
        ManagedKafka mkRemote = mkClient.getByName(kafkaNs, name);

        String readyStatus = readyStatus(Optional.ofNullable(mkRemote)
            .map(ManagedKafka::getStatus)
            .map(ManagedKafkaStatus::getConditions));

        assertEquals("True", readyStatus);
    }

    String readyStatus(Optional<List<ManagedKafkaCondition>> conditions) {
        return conditions.orElseGet(Collections::emptyList)
                .stream()
                .filter(c -> "Ready".equals(c.getType()))
                .map(ManagedKafkaCondition::getStatus)
                .findFirst()
                .orElse("False");
    }

    Map<String, Object> getKafkaAcls(Map<String, Object> kafkaConfigs) {
        return kafkaConfigs.entrySet()
            .stream()
            .filter(e -> e.getKey().startsWith(aclConfigPrefix))
            .filter(e -> !e.getKey().contains(".logging"))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    Map<String, String> getPrincipalAcls(String principalName, Map<String, Object> kafkaAcls) {
        return kafkaAcls.entrySet()
                .stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().toString()))
                .filter(e -> e.getValue().contains("principal=" + principalName))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    void createImagePullSecret() {
        String quayUsername = config.getOptionalValue("quay.username", String.class).orElse("");
        String quayPassword = config.getOptionalValue("quay.password", String.class).orElse("");

        if (quayUsername.isBlank() || quayPassword.isBlank()) {
            return;
        }

        String dockerConfigJson =
                String.format("{ \"auths\": { \"%s\":{ \"auth\": \"%s\" } } }",
                        "quay.io",
                        b64.encodeToString((quayUsername + ":" + quayPassword).getBytes(StandardCharsets.UTF_8)));

        client.secrets()
            .inNamespace(operatorNs)
            .create(new SecretBuilder()
                .withNewMetadata()
                    .withName("rhoas-image-pull-secret")
                .endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .withData(Map.of(".dockerconfigjson", b64.encodeToString(dockerConfigJson.getBytes(StandardCharsets.UTF_8))))
                .build());
    }
}
