package org.bf2.systemtest.unit;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.integration.AbstractST;
import org.bf2.test.executor.Exec;
import org.bf2.test.executor.ExecBuilder;
import org.bf2.test.executor.ExecResult;
import org.bf2.test.k8s.KubeClusterException;
import org.bf2.test.mock.QuarkusKubeMockServer;
import org.bf2.test.mock.QuarkusKubernetesMockServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Class for storing all unit tests of test suite
 * Tests for verifying that test suite is not broken and basic functionality is working properly
 */
@QuarkusTest
@QuarkusTestResource(QuarkusKubeMockServer.class)
public class SuiteUnitTest extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(SuiteUnitTest.class);
    private static final String TEST_NS = "default";

    @QuarkusKubernetesMockServer
    KubernetesServer server;

    @BeforeAll
    void setupMockServer() {
        PodList expectedPodList = new PodListBuilder().withItems(
                new PodBuilder().withNewMetadata().withName("pod1").withNamespace(TEST_NS).endMetadata()
                        .build(),
                new PodBuilder().withNewMetadata().withName("pod2").withNamespace(TEST_NS).endMetadata()
                        .build()).build();
        for (Pod p : expectedPodList.getItems()) {
            server.getClient().pods().inNamespace(TEST_NS).create(p);
        }
    }

    @Test
    void testKubeConnection() {
        List<Pod> pods = kube.client().pods().inNamespace(TEST_NS).list().getItems();
        pods.forEach(pod -> LOGGER.info("Found pod with name {}", pod.getMetadata().getName()));
        assertTrue(pods.size() > 0);
    }

    @Test
    void testKubeRequestFail() {
        assertNull(kube.client().serviceAccounts().withName("non-exists").get());
    }

    @Test
    void testExecutor() {
        ExecResult result = Exec.builder()
                .withCommand("ls", System.getProperty("user.dir"))
                .logToOutput(false)
                .throwErrors(true)
                .timeout(60)
                .exec();
        assertTrue(result.exitStatus());
    }

    @Test
    void testExecutorError() {
        ExecBuilder command = Exec.builder()
                .withCommand("ppppeeeepppaaa", "jenda")
                .logToOutput(false)
                .throwErrors(true)
                .timeout(60);

        assertThrows(KubeClusterException.class, command::exec);
    }
}
