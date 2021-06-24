package org.bf2.performance;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Quantity;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.performance.framework.ManagedKafkaStateAssertionParameterResolver;
import org.bf2.performance.framework.TestCallbackListener;
import org.bf2.performance.framework.TestExceptionCallbackListener;
import org.bf2.performance.framework.TestMetadataCapture;
import org.bf2.systemtest.framework.IndicativeSentences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test base containing shared properties and test callbacks for storing results
 * every test class must extend this TestBase
 */
@ExtendWith(TestCallbackListener.class)
@ExtendWith(TestExceptionCallbackListener.class)
@ExtendWith(ManagedKafkaStateAssertionParameterResolver.class)
@DisplayNameGeneration(IndicativeSentences.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class TestBase {
    protected File testDir;
    protected File instanceDir;

    @BeforeAll
    void beforeAll(TestInfo info) throws IOException {
        testDir = org.bf2.test.TestUtils.getLogPath(org.bf2.test.Environment.LOG_DIR.toString(), info).toFile();
        Files.createDirectories(testDir.toPath());
    }

    @BeforeEach
    void beforeEach(TestInfo info) throws IOException {
        instanceDir = new File(testDir, info.getDisplayName());
        Files.createDirectories(instanceDir.toPath());
    }

    @AfterEach
    void afterEach(TestInfo info) throws IOException {
        Files.write(new File(instanceDir, "test-metadata.json").toPath(), TestMetadataCapture.getInstance().toString().getBytes(StandardCharsets.UTF_8));
    }

    protected void ensureClientClusterCapacityForWorkers(KubeClusterResource ombCluster, int numberOfWorkers, Quantity workerSize) throws IOException {
        BigDecimal requiredWorkerMemory = Quantity.getAmountInBytes(workerSize).multiply(new BigDecimal(numberOfWorkers));
        NodeList nodes = ombCluster.kubeClient().client().nodes().withLabel("node-role.kubernetes.io/worker", "").list();
        BigDecimal nodeMem = BigDecimal.ZERO;
        for (Node node : nodes.getItems()) {
            Quantity nodeMemory = Quantity.parse(String.valueOf(node.getStatus().getAllocatable().get("memory")));
            nodeMem = nodeMem.add(Quantity.getAmountInBytes(nodeMemory));
        }
        assumeTrue(nodeMem.compareTo(requiredWorkerMemory) >= 0,
                String.format("Insufficient worker node memory (%,.2f from %d node(s)) for this test (requires %,.2f).", nodeMem, nodes.getItems().size(), requiredWorkerMemory));
    }
}
