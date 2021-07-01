package org.bf2.performance;

import io.fabric8.kubernetes.api.model.Quantity;
import org.bf2.systemtest.framework.SystemTestEnvironment;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Class which holds environment variables for system tests.
 */
public class PerformanceEnvironment extends SystemTestEnvironment {

    /**
     * Environment VAR names
     */
    private static final String OMB_KUBECONFIG_ENV = "OMB_KUBECONFIG";
    private static final String OMB_TEST_DURATION_ENV = "OMB_TEST_DURATION";
    private static final String OMB_WARMUP_DURATION_ENV = "OMB_WARMUP_DURATION";
    private static final String APPLY_BROKER_QUOTA_ENV = "APPLY_BROKER_QUOTA";
    private static final String KAFKA_KUBECONFIG_ENV = "KAFKA_KUBECONFIG";
    private static final String ENABLE_METRICS_ENV = "ENABLE_METRICS";
    private static final String STRIMZI_EXEC_MAX_LOG_OUTPUT_CHARACTERS_ENV = "STRIMZI_EXEC_MAX_LOG_OUTPUT_CHARACTERS";
    private static final String OMB_COLLECT_LOG_ENV = "OMB_COLLECT_LOG";
    private static final String KAFKA_COLLECT_LOG_ENV = "KAFKA_COLLECT_LOG";
    private static final String MAX_KAFKA_INSTANCES_ENV = "MAX_KAFKA_INSTANCES";
    private static final String NUM_INGRESS_CONTROLLERS_ENV = "NUM_INGRESS_CONTROLLERS";
    private static final String PROVIDED_KAFKA_CLUSTERS_FILE_ENV = "PROVIDED_KAFKA_CLUSTERS_FILE";
    private static final String CONSUMER_PER_SUBSCRIPTION_ENV = "CONSUMER_PER_SUBSCRIPTION";
    private static final String TARGET_RATE_ENV = "TARGET_RATE";
    private static final String WORKERS_PER_INSTANCE_ENV = "WORKERS_PER_INSTANCE";
    private static final String TOPICS_PER_KAFKA_ENV = "TOPICS_PER_KAFKA";
    private static final String PRODUCERS_PER_TOPIC_ENV = "PRODUCERS_PER_TOPIC";
    private static final String PAYLOAD_FILE_SIZE_ENV = "PAYLOAD_FILE_SIZE";
    private static final String KAFKA_VERSION_ENV = "KAFKA_VERSION";
    private static final String STRIMZI_VERSION_ENV = "STRIMZI_VERSION";

    /**
     * Parsed variables into java constants
     */
    public static final String OMB_KUBECONFIG = getOrDefault(OMB_KUBECONFIG_ENV, Constants.SUITE_ROOT + "/client-config");
    public static final String KAFKA_KUBECONFIG = getOrDefault(KAFKA_KUBECONFIG_ENV, Constants.SUITE_ROOT + "/kafka-config");

    public static final Boolean ENABLE_METRICS = getOrDefault(ENABLE_METRICS_ENV, Boolean::parseBoolean, true);
    public static final Duration OMB_TEST_DURATION = getOrDefault(OMB_TEST_DURATION_ENV, Duration::parse, Duration.parse("PT1M"));
    public static final Duration OMB_WARMUP_DURATION = getOrDefault(OMB_WARMUP_DURATION_ENV, Duration::parse, Duration.parse("PT1M"));
    public static final Boolean APPLY_BROKER_QUOTA = getOrDefault(APPLY_BROKER_QUOTA_ENV, Boolean::parseBoolean, Boolean.TRUE);

    public static final int MAXIMUM_EXEC_LOG_CHARACTER_SIZE = getOrDefault(STRIMZI_EXEC_MAX_LOG_OUTPUT_CHARACTERS_ENV, Integer::parseInt, 20000);
    public static final boolean OMB_COLLECT_LOG = getOrDefault(OMB_COLLECT_LOG_ENV, Boolean::parseBoolean, false);
    public static final boolean KAFKA_COLLECT_LOG = getOrDefault(KAFKA_COLLECT_LOG_ENV, Boolean::parseBoolean, false);
    public static final int MAX_KAFKA_INSTANCES = getOrDefault(MAX_KAFKA_INSTANCES_ENV, Integer::parseInt, Integer.MAX_VALUE);
    public static final int NUM_INGRESS_CONTROLLERS = getOrDefault(NUM_INGRESS_CONTROLLERS_ENV, Integer::parseInt, 1);
    public static final Path PROVIDED_KAFKA_CLUSTERS_FILE = getOrDefault(PROVIDED_KAFKA_CLUSTERS_FILE_ENV, Paths::get, Paths.get(Constants.SUITE_ROOT, "provided_clusters.yaml"));
    public static final int CONSUMER_PER_SUBSCRIPTION = getOrDefault(CONSUMER_PER_SUBSCRIPTION_ENV, Integer::parseInt, 1);
    public static final int TARGET_RATE = getOrDefault(TARGET_RATE_ENV, Integer::parseInt, 2000);
    public static final int WORKERS_PER_INSTANCE = getOrDefault(WORKERS_PER_INSTANCE_ENV, Integer::parseInt, 2);
    public static final int TOPICS_PER_KAFKA = getOrDefault(TOPICS_PER_KAFKA_ENV, Integer::parseInt, 1);
    public static final int PRODUCERS_PER_TOPIC = getOrDefault(PRODUCERS_PER_TOPIC_ENV, Integer::parseInt, 1);
    public static final Quantity PAYLOAD_FILE_SIZE = Quantity.parse(getOrDefault(PAYLOAD_FILE_SIZE_ENV, "1Ki"));
    public static final String KAFKA_VERSION = getOrDefault(KAFKA_VERSION_ENV, "2.7.0");
    public static final String STRIMZI_VERSION = getOrDefault(STRIMZI_VERSION_ENV, null);

    public static void logEnvironment() {
        SystemTestEnvironment.logEnvironment();
    }

}
