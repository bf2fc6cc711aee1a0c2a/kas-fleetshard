package org.bf2.performance;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.fabric8.kubernetes.api.model.Quantity;
import io.openmessaging.benchmark.TestResult;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;

/**
 * Test utils contains static help methods
 */
public class TestUtils {
    private static final Logger LOGGER = LogManager.getLogger(TestUtils.class);

    public static Path getLogPath(String folderName, ExtensionContext context) {
        String testMethod = context.getDisplayName();
        Class<?> testClass = context.getTestClass().get();
        return getLogPath(folderName, testClass, testMethod);
    }

    public static Path getLogPath(String folderName, TestInfo info) {
        String testMethod = info.getDisplayName();
        Class<?> testClass = info.getTestClass().get();
        return getLogPath(folderName, testClass, testMethod);
    }

    public static Path getLogPath(String folderName, Class<?> testClass, String testMethod) {
        Path path = Environment.LOG_DIR.resolve(Paths.get(folderName, testClass.getName()));
        if (testMethod != null) {
            path = path.resolve(testMethod.replace("(", "").replace(")", ""));
        }
        return path;
    }

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

    public static void logWithSeparator(String pattern, String text) {
        LOGGER.info("============================================================");
        LOGGER.info(pattern, text);
        LOGGER.info("============================================================");
    }

    public static String payloadFileOfSize(Quantity size) throws IOException {
        byte[] bytes = new byte[Quantity.getAmountInBytes(size).intValueExact()];
        new Random().nextBytes(bytes);

        Path file = Files.createTempFile(String.format("payload-%s-", size), ".data");
        Files.write(file, bytes);
        file.toFile().deleteOnExit();
        return file.toAbsolutePath().toString();
    }
}
