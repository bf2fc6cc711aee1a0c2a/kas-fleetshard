package org.bf2.performance;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.openmessaging.benchmark.TestResult;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.performance.framework.KubeClusterResource;

import java.io.File;
import java.util.List;
import java.util.function.BooleanSupplier;

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
        waitFor(String.format("All pods in namespace %s ready", namespace), 30_000, 600_000, () -> {
            List<Pod> pods = cluster.kubeClient().listPods(namespace);
            for (Pod p : pods) {
                if (!isPodStatusReady(p.getStatus())) {
                    return false;
                }
            }
            return true;
        });
    }

    public static long waitFor(String description, long pollIntervalMs, long timeoutMs, BooleanSupplier ready) {
        return waitFor(description, pollIntervalMs, timeoutMs, ready, () -> {
        });
    }

    /**
     * Wait for specific lambda expression.
     *
     * @param description    description for logging
     * @param pollIntervalMs poll interval in ms
     * @param timeoutMs      timeout in ms
     * @param ready          lambda method for waiting
     * @param onTimeout      lambda method which is called when timeout is reached
     * @return duration in ms
     */
    public static long waitFor(String description, long pollIntervalMs, long timeoutMs, BooleanSupplier ready, Runnable onTimeout) {
        LOGGER.info("Waiting for {}", description);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            boolean result;
            try {
                result = ready.getAsBoolean();
            } catch (Exception e) {
                result = false;
            }
            long timeLeft = deadline - System.currentTimeMillis();
            if (result) {
                return timeLeft;
            }
            if (timeLeft <= 0) {
                onTimeout.run();
                RuntimeException exception = new RuntimeException("Timeout after " + timeoutMs + " ms waiting for " + description);
                exception.printStackTrace();
                throw exception;
            }
            long sleepTime = Math.min(pollIntervalMs, timeLeft);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("{} not ready, will try again in {} ms ({}ms till timeout)", description, sleepTime, timeLeft);
            }
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                return deadline - System.currentTimeMillis();
            }
        }
    }

    public static boolean isPodStatusReady(PodStatus podStatus) {
        return podStatus.getPhase().equals("Running") &&
                podStatus.getContainerStatuses().stream().allMatch(ContainerStatus::getReady);
    }
}
