package org.bf2.systemtest.framework;

import org.bf2.test.Environment;
import org.bf2.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.k8s.KubeClient;
import org.junit.jupiter.api.extension.ExtensionContext;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LogCollector {
    private static final Logger LOGGER = LogManager.getLogger(LogCollector.class);

    /**
     * Calls storing cluster info for connected cluster
     *
     * @param extensionContext
     * @param throwable
     * @throws Throwable
     */
    public static void saveKubernetesState(ExtensionContext extensionContext, Throwable throwable) throws Throwable {
        KubeClient.getInstance().client().pods().inAnyNamespace().list().getItems().forEach(p ->
                LOGGER.info("Pod: {} in ns: {}", p.getMetadata().getName(), p.getMetadata().getNamespace()));

        Path logPath = TestUtils.getLogPath(Environment.LOG_DIR.resolve("failedTest").toString(), extensionContext);
        Files.createDirectories(logPath);
        LOGGER.info("Storing cluster info into {}", logPath.toString());
        try {
            saveClusterState(logPath);
        } catch (IOException ex) {
            LOGGER.warn("Cannot save logs in {}", logPath.toString());
        }
        throw throwable;
    }

    private static void saveClusterState(Path logpath) throws IOException {
//        Files.writeString(logpath.resolve("describe_nodes.log"), cluster.cmdClient().exec(false, false, "describe", "nodes").out());
//        Files.writeString(logpath.resolve("events.log"), cluster.cmdClient().exec(false, false, "get", "events", "--all-namespaces").out());
//        Files.writeString(logpath.resolve("pvs.txt"), cluster.cmdClient().exec(false, false, "describe", "pv").out());
//        Files.writeString(logpath.resolve("storageclass.yml"), cluster.cmdClient().exec(false, false, "get", "storageclass", "-o", "yaml").out());
//        Files.writeString(logpath.resolve("routes.yml"), cluster.cmdClient().exec(false, false, "get", "routes", "--all-namespaces", "-o", "yaml").out());
    }
}
