package org.bf2.performance;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.openmessaging.benchmark.TestResult;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

}
