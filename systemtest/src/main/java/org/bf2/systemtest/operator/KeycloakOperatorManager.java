package org.bf2.systemtest.operator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Gettable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.framework.KeycloakInstance;
import org.bf2.systemtest.framework.SecurityUtils;
import org.bf2.systemtest.framework.SystemTestEnvironment;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class KeycloakOperatorManager {
    private static final Logger LOGGER = LogManager.getLogger(KeycloakOperatorManager.class);
    public static final String OPERATOR_NS = "kas-fleetshard-keycloak";
    private static final String KC_NAME = "keycloak";
    private static final String KEYCLOAK_RESOURCE_BASEURL = String.format("https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/%s/kubernetes/", SystemTestEnvironment.KEYCLOAK_VERSION);
    private static final List<HasMetadata> INSTALLED_RESOURCES = new LinkedList<>();

    public static CompletableFuture<Void> installKeycloak(KubeClient kubeClient) throws Exception {
        if (SystemTestEnvironment.INSTALL_KEYCLOAK) {
            LOGGER.info("Installing Keycloak : {}", OPERATOR_NS);
            KubernetesClient client = kubeClient.client();

            return CompletableFuture.runAsync(() -> {
                    createNamespace(kubeClient);
                    createKeycloakOperator(client);
                    awaitPodReady("Keycloak Operator", "app.kubernetes.io/name", "keycloak-operator");
                    createOrReplaceAll(client, "keycloak-postgres.yml");
                    awaitPodReady("Keycloak Postgres DB", "app", "postgresql-db");
                    createSecrets(client);
                    createOrReplaceAll(client, "keycloak-instance.yml");
                    awaitPodReady("Keycloak Application", "app", KC_NAME);
                    createOrReplaceAll(client, "keycloak-realm.yml");
                    TestUtils.waitFor("Keycloak realm import", 1_000, 600_000, KeycloakOperatorManager::realmImportSucceeded);
                    AtomicInteger countdown = new AtomicInteger(5);
                    TestUtils.waitFor("Keycloak Pod rolling buffer", 1_000, 20_000, () -> countdown.getAndDecrement() < 1);
                    awaitPodReady("Keycloak Application", "app", KC_NAME);
                    LOGGER.info("Keycloak instance is ready");
                });
        } else {
            LOGGER.info("Keycloak is not installed suite will use values from env vars for oauth");
            return CompletableFuture.completedFuture(null);
        }
    }

    static void createNamespace(KubeClient kubeClient) {
        if (Boolean.TRUE.equals(kubeClient.client().namespaces().withName(OPERATOR_NS).delete())) {
            TestUtils.waitFor("Existing keycloak namespace to be removed",
                    1_000, 600_000,
                    () -> !kubeClient.namespaceExists(OPERATOR_NS));
        }

        Namespace ns = kubeClient.client().namespaces()
            .createOrReplace(new NamespaceBuilder()
                    .withNewMetadata()
                        .withName(OPERATOR_NS)
                    .endMetadata()
                    .build());
        INSTALLED_RESOURCES.add(ns);
    }

    static void createSecrets(KubernetesClient client) {
        SecurityUtils.TlsConfig tls;

        try {
            tls = SecurityUtils.getTLSConfig(OPERATOR_NS + ".svc");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Stream.of(
                new SecretBuilder()
                    .withNewMetadata()
                        .withName(KeycloakInstance.KEYCLOAK_SECRET_NAME)
                        .withNamespace(OPERATOR_NS)
                    .endMetadata()
                    .withType("kubernetes.io/tls")
                    .withData(Map.of(
                            "tls.crt", base64Encode(tls.getCert()),
                            "tls.key", base64Encode(tls.getKey()))),
                new SecretBuilder()
                    .withNewMetadata()
                        .withName("keycloak-db-secret")
                        .withNamespace(OPERATOR_NS)
                    .endMetadata()
                    .withData(Map.of(
                            "username", base64Encode("postgres"),
                            "password", base64Encode("postgrespass"))))
            .map(SecretBuilder::build)
            .map(client.secrets().inNamespace(OPERATOR_NS)::createOrReplace)
            .forEach(INSTALLED_RESOURCES::add);
    }

    static String base64Encode(String value) {
        return new String(Base64.getEncoder().encode(value.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    static void createKeycloakOperator(KubernetesClient client) {
        Stream.of(
                "keycloaks.k8s.keycloak.org-v1.yml",
                "keycloakrealmimports.k8s.keycloak.org-v1.yml",
                "kubernetes.yml")
            .map(KEYCLOAK_RESOURCE_BASEURL::concat)
            .map(t -> {
                try {
                    return new URL(t).openStream();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            })
            .map(client::load)
            .map(Gettable::get)
            .flatMap(Collection::stream)
            .forEach(resource -> createOrReplace(client, resource));
    }

    static void createOrReplaceAll(KubernetesClient client, String resourceName) {
        try (InputStream stream = KeycloakOperatorManager.class.getClassLoader().getResourceAsStream(resourceName)) {
            client.load(stream).get().stream().forEach(resource -> createOrReplace(client, resource));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void createOrReplace(KubernetesClient client, HasMetadata resource) {
        resource.getMetadata().setNamespace(OPERATOR_NS);
        client.resource(resource).inNamespace(OPERATOR_NS).createOrReplace();
        INSTALLED_RESOURCES.add(resource);
    }

    static void awaitPodReady(String description, String labelName, String labelValue) {
        TestUtils.waitFor(description + " ready", 1_000, 600_000, () -> isPodReady(labelName, labelValue));
    }

    static boolean isPodReady(String labelName, String labelValue) {
        return TestUtils.isPodReady(KubeClient.getInstance()
                .client()
                .pods()
                .inNamespace(OPERATOR_NS)
                .withLabel(labelName, labelValue)
                .list()
                .getItems()
                .stream()
                .findFirst()
                .orElse(null));
    }

    static boolean realmImportSucceeded() {
        return Optional.ofNullable(KubeClient.getInstance()
                .client()
                .batch()
                .v1()
                .jobs()
                .inNamespace(OPERATOR_NS)
                .withName("demo-realm")
                .get())
            .map(job -> Optional.of(job)
                        .map(Job::getStatus)
                        .map(JobStatus::getConditions)
                        .orElseGet(Collections::emptyList)
                        .stream()
                        .anyMatch(c -> "Complete".equals(c.getType()) && "True".equals(c.getStatus())) ? job : null)
            .filter(Objects::nonNull)
            .map(job -> job.getStatus().getSucceeded())
            .filter(Objects::nonNull)
            .map(succeeded -> succeeded > 0)
            .orElse(Boolean.FALSE);
    }

    public static CompletableFuture<Void> uninstallKeycloak(KubeClient kubeClient) {
        if (SystemTestEnvironment.INSTALL_KEYCLOAK && kubeClient.namespaceExists(OPERATOR_NS) && !SystemTestEnvironment.SKIP_TEARDOWN) {
            LOGGER.info("Deleting Keycloak : {}", OPERATOR_NS);
            KubernetesClient client = kubeClient.client();

            INSTALLED_RESOURCES.forEach(resource -> client.resource(resource).inNamespace(OPERATOR_NS).delete());

            return TestUtils.asyncWaitFor("Delete Keycloak", 2_000, FleetShardOperatorManager.DELETE_TIMEOUT_MS, () ->
                client.pods().inNamespace(OPERATOR_NS).list().getItems().stream().noneMatch(pod ->
                            pod.getMetadata().getName().contains("keycloak-0")) &&
                            !kubeClient.namespaceExists(OPERATOR_NS));
        } else {
            LOGGER.info("No need to uninstall keycloak");
            return CompletableFuture.completedFuture(null);
        }
    }
}
