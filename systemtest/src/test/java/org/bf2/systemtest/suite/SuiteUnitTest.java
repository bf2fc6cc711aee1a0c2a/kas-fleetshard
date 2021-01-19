package org.bf2.systemtest.suite;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.EmptyDefaultKubernetesMockServerTestResource;
import io.quarkus.test.kubernetes.client.MockServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.executor.Exec;
import org.bf2.systemtest.executor.ExecBuilder;
import org.bf2.systemtest.executor.ExecResult;
import org.bf2.systemtest.framework.IndicativeSentences;
import org.bf2.systemtest.k8s.KubeClient;
import org.bf2.systemtest.k8s.KubeClusterException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.HttpURLConnection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Class for storing all unit tests of test suite
 * Tests for verifying that test suite is not broken and basic functionality is working properly
 */
@QuarkusTest
@QuarkusTestResource(EmptyDefaultKubernetesMockServerTestResource.class)
@DisplayNameGeneration(IndicativeSentences.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SuiteUnitTest {
    private static final Logger LOGGER = LogManager.getLogger(SuiteUnitTest.class);

    @MockServer
    KubernetesMockServer server;

    @BeforeAll
    void setupMockServer() {
        PodList expectedPodList = new PodListBuilder().withItems(
                new PodBuilder().withNewMetadata().withName("pod1").withNamespace("default").endMetadata()
                        .build(),
                new PodBuilder().withNewMetadata().withName("pod2").withNamespace("default").endMetadata()
                        .build()).build();
        server.expect().get().withPath("/api/v1/namespaces/default/pods")
                .andReturn(HttpURLConnection.HTTP_OK, expectedPodList)
                .once();
    }

    @Test
    void testKubeConnection() {
        List<Pod> pods = KubeClient.getInstance().client().pods().inNamespace("default").list().getItems();
        pods.forEach(pod -> LOGGER.info("Found pod with name {}", pod.getMetadata().getName()));
        assertTrue(pods.size() > 0);
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
