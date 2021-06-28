package org.bf2.performance;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.openmessaging.benchmark.TestResult;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.performance.framework.KubeClusterResource;

import java.io.File;
import java.util.List;

/**
 * Test utils contains static help methods
 */
public class TestUtils {
    private static final Logger LOGGER = LogManager.getLogger(TestUtils.class);

    public static double getAvg(List<? extends Number> obj) {
        double sum = 0.0;
        for (Number num : obj) {
            sum += num.intValue();
        }
        double avg = sum / obj.size();
        return avg;
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

}
