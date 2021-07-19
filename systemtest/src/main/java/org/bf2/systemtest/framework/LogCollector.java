package org.bf2.systemtest.framework;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.Environment;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LogCollector {
    private static final Logger LOGGER = LogManager.getLogger(LogCollector.class);

    /**
     * Calls storing cluster info for connected cluster
     */
    public static void saveKubernetesState(ExtensionContext extensionContext, Throwable throwable) throws Throwable {
        LOGGER.warn("Printing all pods on cluster");
        KubeClient.getInstance().client().pods().inAnyNamespace().list().getItems().forEach(p ->
                LOGGER.info("Pod: {} in ns: {} with phase: {}",
                        p.getMetadata().getName(),
                        p.getMetadata().getNamespace(),
                        p.getStatus().getPhase()));

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
        KubeClient kube = KubeClient.getInstance();
        Files.writeString(logpath.resolve("describe-cluster-nodes.log"), kube.cmdClient().exec(false, false, "describe", "nodes").out());
        Files.writeString(logpath.resolve("all-events.log"), kube.cmdClient().exec(false, false, "get", "events", "--all-namespaces").out());
        Files.writeString(logpath.resolve("pvs.log"), kube.cmdClient().exec(false, false, "describe", "pv").out());
        Files.writeString(logpath.resolve("operator-routes.yml"), kube.cmdClient().exec(false, false, "get", "routes", "-n", FleetShardOperatorManager.OPERATOR_NS, "-o", "yaml").out());
        Files.writeString(logpath.resolve("operator-services.yml"), kube.cmdClient().exec(false, false, "get", "service", "-n", FleetShardOperatorManager.OPERATOR_NS, "-o", "yaml").out());
        Files.writeString(logpath.resolve("kas-fleetshard-operator-pods.yml"), kube.cmdClient().exec(false, false, "get", "pod", "-l", "app=" + FleetShardOperatorManager.OPERATOR_NAME, "--all-namespaces", "-o", "yaml").out());
        Files.writeString(logpath.resolve("strimzi-kafka-pods.yml"), kube.cmdClient().exec(false, false, "get", "pod", "-l", "app.kubernetes.io/managed-by=strimzi-cluster-operator", "--all-namespaces", "-o", "yaml").out());
        Files.writeString(logpath.resolve("managedkafkas.yml"), kube.cmdClient().exec(false, false, "get", "managedkafka", "--all-namespaces", "-o", "yaml").out());
        Files.writeString(logpath.resolve("kafkas.yml"), kube.cmdClient().exec(false, false, "get", "kafka", "-l", "app.kubernetes.io/managed-by=" + FleetShardOperatorManager.OPERATOR_NAME, "--all-namespaces", "-o", "yaml").out());
        Files.writeString(logpath.resolve("pods-managed-by-operator.yml"), kube.cmdClient().exec(false, false, "get", "pods", "-l", "app.kubernetes.io/managed-by=" + FleetShardOperatorManager.OPERATOR_NAME, "--all-namespaces", "-o", "yaml").out());
        Files.writeString(logpath.resolve("operator-namespace-events.yml"), kube.cmdClient().exec(false, false, "get", "events", "-n", FleetShardOperatorManager.OPERATOR_NS).out());
        Files.writeString(logpath.resolve("operator.log"), kube.cmdClient().exec(false, false, "logs", "deployment/" + FleetShardOperatorManager.OPERATOR_NAME, "-n", FleetShardOperatorManager.OPERATOR_NS).out());
        Files.writeString(logpath.resolve("sync.log"), kube.cmdClient().exec(false, false, "logs", "deployment/" + FleetShardOperatorManager.SYNC_NAME, "-n", FleetShardOperatorManager.OPERATOR_NS).out());
        Files.writeString(logpath.resolve("strimzi-operators.log"), kube.cmdClient().exec(false, false, "logs", "-l", "name=strimzi-cluster-operator", "--tail", "-1", "-n", StrimziOperatorManager.getStrimziOperatorNamespace()).out());
    }
}
