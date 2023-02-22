package org.bf2.systemtest.unit;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.framework.ExtensionContextParameterResolver;
import org.bf2.systemtest.framework.IndicativeSentences;
import org.bf2.systemtest.framework.KeycloakInstance;
import org.bf2.systemtest.framework.ParallelTest;
import org.bf2.systemtest.framework.SecurityUtils;
import org.bf2.systemtest.framework.SequentialTest;
import org.bf2.systemtest.framework.TestCallbackListener;
import org.bf2.systemtest.framework.TestExceptionCallbackListener;
import org.bf2.test.executor.Exec;
import org.bf2.test.executor.ExecBuilder;
import org.bf2.test.executor.ExecResult;
import org.bf2.test.k8s.KubeClient;
import org.bf2.test.k8s.KubeClusterException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

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
@QuarkusTestResource(KubernetesServerTestResource.class)
@ExtendWith(TestCallbackListener.class)
@ExtendWith(ExtensionContextParameterResolver.class)
@ExtendWith(TestExceptionCallbackListener.class)
@DisplayNameGeneration(IndicativeSentences.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SuiteUnitTest {

    private static final Logger LOGGER = LogManager.getLogger(SuiteUnitTest.class);
    private static final String TEST_NS = "default";
    private final KubeClient kube = KubeClient.getInstance();

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
                            .withName(KeycloakInstance.ADMIN_SECRET)
                        .endMetadata()
                        .withData(
                                Map.of(
                                        "username", "YWRtaW4=",
                                        "password", "YWRtaW4="))
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
        assertEquals("https://keycloak-service.keycloak.svc:8443/auth/realms/demo/protocol/openid-connect/certs", k.getJwksEndpointUri());
        assertEquals("admin", k.getUsername());
        assertEquals("admin", k.getPassword());
        assertNotNull(k.getKeycloakCert());
    }
}
