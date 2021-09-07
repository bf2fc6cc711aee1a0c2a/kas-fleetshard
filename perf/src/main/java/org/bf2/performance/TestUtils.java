package org.bf2.performance;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.openmessaging.benchmark.TestResult;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.performance.framework.KubeClusterResource;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Test utils contains static help methods
 */
public class TestUtils {
    private static final Logger LOGGER = LogManager.getLogger(TestUtils.class);

    /**
     * These margins assume quite a few openshift- system pods will need to be scheduled + additional margin for safety
     */
    static final Quantity NODE_MEMORY_MARGIN = Quantity.parse("2.5Gi");
    static final Quantity NODE_CPU_MARGIN = Quantity.parse("500m");

    public static class AvailableResources {
        public long memoryBytes;
        public long cpuMillis;
    }

    public static double getAvg(List<? extends Number> obj) {
        return obj.stream().collect(Collectors.averagingDouble(Number::doubleValue));
    }

    public static double getMedian(List<? extends Number> obj) {
        List<Number> sorted = obj.stream().sorted().collect(Collectors.toList());
        int index = sorted.size() / 2;
        if ((sorted.size() % 2) == 0) {
            return (sorted.get(index - 1).doubleValue() + sorted.get(index).doubleValue()) / 2;
        }
        return sorted.get(index).doubleValue();
    }

    public static void createJsonObject(File testDir, TestResult testResult) throws Exception {
        JsonObject jsonObj = new JsonObject();
        jsonObj.put("name", testResult.workload);
        jsonObj.put("driver", testResult.driver);
        jsonObj.put("publishRateAvg", TestUtils.getAvg(testResult.publishRate));
        jsonObj.put("consumeRateAvg", TestUtils.getAvg(testResult.consumeRate));
        jsonObj.put("backlogAvg", TestUtils.getAvg(testResult.backlog));
        jsonObj.put("publishLatencyAvg", TestUtils.getAvg(testResult.publishLatencyAvg));
        jsonObj.put("aggregatedPublishLatencyAvg", testResult.aggregatedPublishLatencyAvg);
        jsonObj.put("aggregatedPublishLatencyMax", testResult.aggregatedPublishLatencyMax);
        jsonObj.put("endToEndLatencyAvg", TestUtils.getAvg(testResult.endToEndLatencyAvg));
        jsonObj.put("endToEndLatencyMax", TestUtils.getAvg(testResult.endToEndLatencyMax));
        jsonObj.put("aggregatedEndToEndLatencyAvg", testResult.aggregatedEndToEndLatencyAvg);
        jsonObj.put("aggregatedEndToEndLatencyMax", testResult.aggregatedEndToEndLatencyMax);

        ObjectMapper mapper = new ObjectMapper();
        ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());
        File resultFile = new File(testDir, "perf_result.json");
        writer.writeValue(resultFile, jsonObj);
        LOGGER.info("Wrote perf results to {}", resultFile.getAbsolutePath());
    }

    public static void drainNode(KubeClusterResource cluster, String nodeName) {
        LOGGER.info("Cluster node {} is going to drain", nodeName);
        //setNodeSchedule(cluster, nodeName, false);
        cluster.cmdKubeClient().exec("adm", "drain", nodeName, "--delete-local-data", "--force", "--ignore-daemonsets");
    }

    public static void setNodeSchedule(KubeClusterResource cluster, String node, boolean schedule) {
        LOGGER.info("Set {} schedule {}", node, schedule);
        cluster.cmdKubeClient().exec("adm", schedule ? "uncordon" : "cordon", node);
    }

    public static void waitUntilAllPodsReady(KubeClusterResource cluster, String namespace) {
        org.bf2.test.TestUtils.waitFor(String.format("All pods in namespace %s ready", namespace), 30_000, 600_000,
                () -> cluster.kubeClient().listPods(namespace).stream().allMatch(Readiness::isPodReady));
    }

    /**
     * Makes a simplistic scheduling assumption that nearly everything on the node passed in will be allocatable to the prospective resources
     */
    public static int getNumberThatWillFit(List<Node> nodes, Quantity memorySize, Quantity cpuSize) throws IOException {
        int willFit = 0;
        for (Node node : nodes) {
            AvailableResources resources = getMaxAvailableResources(node);

            willFit += Math.min(resources.memoryBytes/Quantity.getAmountInBytes(memorySize).longValue(), resources.cpuMillis/Quantity.getAmountInBytes(cpuSize).movePointRight(3).longValue());
        }
        return willFit;
    }

    public static AvailableResources getMaxAvailableResources(Node node) {
        Quantity nodeMemory = Quantity.parse(String.valueOf(node.getStatus().getAllocatable().get("memory")));
        Quantity nodeCpu = Quantity.parse(String.valueOf(node.getStatus().getAllocatable().get("cpu")));

        AvailableResources resources = new AvailableResources();
        resources.memoryBytes = Quantity.getAmountInBytes(nodeMemory).longValue() - Quantity.getAmountInBytes(NODE_MEMORY_MARGIN).longValue();
        resources.cpuMillis = Quantity.getAmountInBytes(nodeCpu).movePointRight(3).longValue() - Quantity.getAmountInBytes(NODE_CPU_MARGIN).movePointRight(3).longValue();
        return resources;
    }

}
