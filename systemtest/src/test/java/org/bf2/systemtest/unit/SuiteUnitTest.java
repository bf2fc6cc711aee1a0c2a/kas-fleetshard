package org.bf2.systemtest.unit;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.framework.KeycloakInstance;
import org.bf2.systemtest.framework.ParallelTest;
import org.bf2.systemtest.framework.SecurityUtils;
import org.bf2.systemtest.framework.SequentialTest;
import org.bf2.systemtest.integration.AbstractST;
import org.bf2.test.executor.Exec;
import org.bf2.test.executor.ExecBuilder;
import org.bf2.test.executor.ExecResult;
import org.bf2.test.k8s.KubeClusterException;
import org.junit.jupiter.api.BeforeAll;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Class for storing all unit tests of test suite
 * Tests for verifying that test suite is not broken and basic functionality is working properly
 */
@QuarkusTest
@WithKubernetesTestServer
public class SuiteUnitTest extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(SuiteUnitTest.class);
    private static final String TEST_NS = "default";

    @KubernetesTestServer
    KubernetesServer mockServer;

    @BeforeAll
    void setupMockServer() throws Exception {
        PodList expectedPodList = new PodListBuilder().withItems(
                new PodBuilder().withNewMetadata().withName("pod1").withNamespace(TEST_NS).endMetadata()
                        .build(),
                new PodBuilder().withNewMetadata().withName("pod2").withNamespace(TEST_NS).endMetadata()
                        .build()).build();
        for (Pod p : expectedPodList.getItems()) {
            mockServer.getClient().pods().inNamespace(TEST_NS).create(p);
        }

        mockServer.getClient().namespaces().createOrReplace(new NamespaceBuilder().withNewMetadata().withName("keycloak").endMetadata().build());
        mockServer.getClient().pods().inNamespace("keycloak").createOrReplace(
                new PodBuilder().withNewMetadata().withName("keycloak-0").withNamespace("keycloak").endMetadata().build());

        SecurityUtils.TlsConfig tls = SecurityUtils.getTLSConfig("keycloak.svc");
        Secret keycloakCert = new SecretBuilder()
                .withNewMetadata()
                .withName("sso-x509-https-secret")
                .withNamespace("keycloak")
                .endMetadata()
                .withType("kubernetes.io/tls")
                .withData(Map.of(
                        "tls.crt", new String(Base64.getEncoder().encode(tls.getCert().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8),
                        "tls.key", new String(Base64.getEncoder().encode(tls.getKey().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                )
                .build();
        mockServer.getClient().secrets().inNamespace("keycloak").createOrReplace(keycloakCert);
        mockServer.getClient().secrets().inNamespace("keycloak").createOrReplace(
                new SecretBuilder()
                        .withNewMetadata()
                        .withName("credential-example-keycloak")
                        .endMetadata()
                        .withData(
                                Map.of(
                                        "ADMIN_USERNAME", "YWRtaW4=",
                                        "ADMIN_PASSWORD", "YWRtaW4="))
                        .build());
    }

    @ParallelTest
    void testKubeConnection() {
        List<Pod> pods = kube.client().pods().inNamespace(TEST_NS).list().getItems();
        pods.forEach(pod -> LOGGER.info("Found pod with name {}", pod.getMetadata().getName()));
        assertTrue(pods.size() > 0);
    }

    @ParallelTest
    void testKubeRequestFail() {
        assertNull(kube.client().serviceAccounts().withName("non-exists").get());
    }

    @ParallelTest
    void testExecutor() {
        ExecResult result = Exec.builder()
                .withCommand("ls", System.getProperty("user.dir"))
                .logToOutput(false)
                .throwErrors(true)
                .timeout(60)
                .exec();
        assertTrue(result.exitStatus());
    }

    @SequentialTest
    void testExecutorError() {
        ExecBuilder command = Exec.builder()
                .withCommand("ppppeeeepppaaa", "jenda")
                .logToOutput(false)
                .throwErrors(true)
                .timeout(60);

        assertThrows(KubeClusterException.class, command::exec);
    }

    @ParallelTest
    void testKeycloakInstance() {
        KeycloakInstance k = new KeycloakInstance("keycloak");
        assertEquals("https://keycloak.keycloak.svc:8443/auth/realms/demo/protocol/openid-connect/certs", k.getJwksEndpointUri());
        assertEquals("admin", k.getUsername());
        assertEquals("admin", k.getPassword());
        assertNotNull(k.getKeycloakCert());
    }
}
