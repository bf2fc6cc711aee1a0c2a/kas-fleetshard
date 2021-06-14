package org.bf2.systemtest.operator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.framework.SecurityUtils;
import org.bf2.test.Environment;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class KeycloakOperatorManager {
    private static final Logger LOGGER = LogManager.getLogger(KeycloakOperatorManager.class);
    public static final String OPERATOR_NS = "kas-fleetshard-keycloak";
    private static final List<HasMetadata> INSTALLED_RESOURCES = new LinkedList<>();

    //env vars
    private static final String KEYCLOAK_VERSION = Environment.getOrDefault("KEYCLOAK_VERSION", "12.0.1");
    public static final boolean INSTALL_KEYCLOAK = Environment.getOrDefault("INSTALL_KEYCLOAK", Boolean::parseBoolean, false);

    public static CompletableFuture<Void> installKeycloak(KubeClient kubeClient) throws Exception {
        if (INSTALL_KEYCLOAK) {
            LOGGER.info("Installing Keycloak : {}", OPERATOR_NS);

            kubeClient.client().namespaces().createOrReplace(new NamespaceBuilder().withNewMetadata().withName(OPERATOR_NS).endMetadata().build());

            Map<String, String> tls = SecurityUtils.getTLSConfig(OPERATOR_NS + ".svc");

            Secret keycloakCert = new SecretBuilder()
                    .withNewMetadata()
                    .withName("sso-x509-https-secret")
                    .withNamespace(OPERATOR_NS)
                    .endMetadata()
                    .withType("kubernetes.io/tls")
                    .withData(Map.of(
                            "tls.crt", new String(Base64.getEncoder().encode(tls.get("cert").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8),
                            "tls.key", new String(Base64.getEncoder().encode(tls.get("key").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                    )
                    .build();
            kubeClient.client().secrets().inNamespace(OPERATOR_NS).createOrReplace(keycloakCert);

            List<String> keycloakInstallFiles = Arrays.asList(
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/service_account.yaml",
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/role_binding.yaml",
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/role.yaml",
                    "https://raw.githubusercontent.com/keycloak/keycloak-operator/" + KEYCLOAK_VERSION + "/deploy/cluster_roles/cluster_role_binding.yaml",
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/cluster_roles/cluster_role.yaml",
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/crds/keycloak.org_keycloakbackups_crd.yaml",
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/crds/keycloak.org_keycloakclients_crd.yaml",
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/crds/keycloak.org_keycloakrealms_crd.yaml",
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/crds/keycloak.org_keycloaks_crd.yaml",
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/crds/keycloak.org_keycloakusers_crd.yaml",
                    "https://github.com/keycloak/keycloak-operator/raw/" + KEYCLOAK_VERSION + "/deploy/operator.yaml"
            );

            for (String urlString : keycloakInstallFiles) {
                URL url = new URL(urlString);
                INSTALLED_RESOURCES.add(kubeClient.client().load(url.openStream()).get().get(0));
            }

            for (HasMetadata resource : INSTALLED_RESOURCES) {
                resource.getMetadata().setNamespace(OPERATOR_NS);
                kubeClient.client().resource(resource).inNamespace(OPERATOR_NS).createOrReplace();
            }

            kubeClient.cmdClient().namespace(OPERATOR_NS)
                    .execInCurrentNamespace("apply", "-f",
                            Paths.get(Environment.SUITE_ROOT, "src", "main", "resources", "keycloak.yml").toAbsolutePath().toString());

            LOGGER.info("Done installing Keycloak : {}", OPERATOR_NS);
            return TestUtils.asyncWaitFor("Keycloak instance ready", 1_000, 600_000, () ->
                    TestUtils.isPodReady(KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                            .list().getItems().stream().filter(pod ->
                                    pod.getMetadata().getName().contains("keycloak-0")).findFirst().get()));
        } else {
            LOGGER.info("Keycloak is not installed suite will use values from env vars for oauth");
            return CompletableFuture.completedFuture(null);
        }
    }

    public static CompletableFuture<Void> uninstallKeycloak(KubeClient kubeClient) {
        if (INSTALL_KEYCLOAK && kubeClient.namespaceExists(OPERATOR_NS) && !Environment.SKIP_TEARDOWN) {
            LOGGER.info("Deleting Keycloak : {}", OPERATOR_NS);
            kubeClient.cmdClient().namespace(OPERATOR_NS).execInCurrentNamespace("delete", "-f",
                    Paths.get(Environment.SUITE_ROOT, "src", "main", "resources", "keycloak.yml").toAbsolutePath().toString());
            kubeClient.cmdClient().namespace(OPERATOR_NS)
                    .execInCurrentNamespace("delete", "keycloak", "--all");
            INSTALLED_RESOURCES.forEach(resource -> kubeClient.client().resource(resource).inNamespace(OPERATOR_NS).delete());
            kubeClient.client().namespaces().withName(OPERATOR_NS).delete();
            return TestUtils.asyncWaitFor("Delete Keycloak", 2_000, 120_000, () ->
                    kubeClient.client().pods().inNamespace(OPERATOR_NS).list().getItems().stream().noneMatch(pod ->
                            pod.getMetadata().getName().contains("keycloak-0")) &&
                            !kubeClient.namespaceExists(OPERATOR_NS));
        } else {
            LOGGER.info("No need to uninstall keycloak");
            return CompletableFuture.completedFuture(null);
        }
    }
}
