package io.kafka.performance;

import com.google.common.base.Strings;
import io.kafka.performance.executor.ExecBuilder;
import io.kafka.performance.executor.ExecResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.stream.Collectors;

public class ThanosSender {
    private static final Logger LOGGER = LogManager.getLogger(ThanosSender.class);

    public static void sendOmbData(File jsonResults, Map<String, String> labels) throws InterruptedException {

        if (Files.exists(Environment.REMOTE_WRITE_BIN)) {
            if (Strings.isNullOrEmpty(Environment.THANOS_URL)) {
                LOGGER.warn("Results is not sent to thanos: THANOS_URL env variable is empty");
            } else {
                for (int loop = 1; loop <= 10; loop++) {
                    ExecResult results = new ExecBuilder()
                            .withCommand(Environment.REMOTE_WRITE_BIN.toString(),
                                    "-thanos", Environment.THANOS_URL,
                                    "-results", jsonResults.toPath().toString(),
                                    "-insecure",
                                    "-token", Strings.isNullOrEmpty(Environment.THANOS_TOKEN) ? "''" : Environment.THANOS_TOKEN,
                                    "-labels", labels.keySet().stream().map(key -> key + ":" + labels.get(key)).collect(Collectors.joining(",")))
                            .throwErrors(false)
                            .exec();
                    if (results.exitStatus()) {
                        LOGGER.info("Data sent to Thanos");
                        break;
                    } else if (loop >= 10) {
                        LOGGER.error("Thanos sending failed");
                    }
                    Thread.sleep(5_000);
                }
            }
        } else {
            LOGGER.warn("Results is not sent to thanos: remote-write binary does not exist in {}", Environment.REMOTE_WRITE_BIN);
        }
    }
}
