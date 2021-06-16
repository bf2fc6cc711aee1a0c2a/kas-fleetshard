package org.bf2.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Quantity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * Class which holds environment variables for system tests.
 */
public class Environment {

    private static final Logger LOGGER = LogManager.getLogger(Environment.class);
    private static final Map<String, String> VALUES = new HashMap<>();
    private static final JsonNode JSON_DATA = loadConfigurationFile();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm");

    /**
     * Specify the system test configuration file path from an environmental variable
     */
    private static final String CONFIG_FILE_PATH_ENVAR = "CONFIG_PATH";
    private static String config;

    /**
     * Environment VAR names
     */
    private static final String OMB_KUBECONFIG_ENV = "OMB_KUBECONFIG";
    private static final String OMB_TEST_DURATION_ENV = "OMB_TEST_DURATION";
    private static final String OMB_WARMUP_DURATION_ENV = "OMB_WARMUP_DURATION";
    private static final String APPLY_BROKER_QUOTA_ENV = "APPLY_BROKER_QUOTA";
    private static final String KAFKA_KUBECONFIG_ENV = "KAFKA_KUBECONFIG";
    private static final String OMB_DIR_ENV = "OMB_DIR";
    private static final String LOG_DIR_ENV = "LOG_DIR";
    private static final String REMOTE_WRITE_DIR_ENV = "REMOTE_WRITE_DIR";
    private static final String MONITORING_STUFF_DIR_ENV = "MONITORING_STUFF_DIR";
    private static final String STRIMZI_INSTALL_MODE_ENV = "STRIMZI_INSTALL_MODE";
    private static final String STRIMZI_EXEC_MAX_LOG_OUTPUT_CHARACTERS_ENV = "STRIMZI_EXEC_MAX_LOG_OUTPUT_CHARACTERS";
    private static final String THANOS_URL_ENV = "THANOS_URL";
    private static final String THANOS_TOKEN_ENV = "THANOS_TOKEN";
    private static final String OBSERVATORIUM_ROUTE_ENV = "OBSERVATORIUM_ROUTE";
    private static final String OMB_COLLECT_LOG_ENV = "OMB_COLLECT_LOG";
    private static final String KAFKA_COLLECT_LOG_ENV = "KAFKA_COLLECT_LOG";
    private static final String MAX_KAFKA_INSTANCES_ENV = "MAX_KAFKA_INSTANCES";
    private static final String NUM_INGRESS_CONTROLLERS_ENV = "NUM_INGRESS_CONTROLLERS";
    private static final String PROVIDED_KAFKA_CLUSTERS_FILE_ENV = "PROVIDED_KAFKA_CLUSTERS_FILE";
    private static final String STRIMZI_VERSION_ENV = "STRIMZI_VERSION";
    private static final String ENABLE_DRAIN_CLEANER_ENV = "ENABLE_DRAIN_CLEANER";
    private static final String CONSUMER_PER_SUBSCRIPTION_ENV = "CONSUMER_PER_SUBSCRIPTION";
    private static final String TARGET_RATE_ENV = "TARGET_RATE";
    private static final String WORKERS_PER_INSTANCE_ENV = "WORKERS_PER_INSTANCE";
    private static final String TOPICS_PER_KAFKA_ENV = "TOPICS_PER_KAFKA";
    private static final String PRODUCERS_PER_TOPIC_ENV = "PRODUCERS_PER_TOPIC";
    private static final String PAYLOAD_FILE_SIZE_ENV = "PAYLOAD_FILE_SIZE";

    /**
     * Parsed variables into java constants
     */
    public static final String OMB_KUBECONFIG = getOrDefault(OMB_KUBECONFIG_ENV, Constants.SUITE_ROOT + "/client-config");
    public static final String KAFKA_KUBECONFIG = getOrDefault(KAFKA_KUBECONFIG_ENV, Constants.SUITE_ROOT + "/kafka-config");

    public static final String OMB_DIR = getOrDefault(OMB_DIR_ENV, Constants.SUITE_ROOT + "/openmessaging-benchmark");
    public static final Path LOG_DIR = getOrDefault(LOG_DIR_ENV, Paths::get, Paths.get(Constants.SUITE_ROOT, "target", "logs")).resolve("test-results-" + DATE_FORMAT.format(LocalDateTime.now()));
    public static final InstallMode STRIMZI_INSTALL_MODE = InstallMode.valueOf(getOrDefault(STRIMZI_INSTALL_MODE_ENV, "CLUSTER"));
    public static final String THANOS_URL = getOrDefault(THANOS_URL_ENV, "");
    public static final String OBSERVATORIUM_ROUTE = getOrDefault(OBSERVATORIUM_ROUTE_ENV, "");
    public static final String THANOS_TOKEN = getOrDefault(THANOS_TOKEN_ENV, "");
    public static final Path REMOTE_WRITE_BIN = getOrDefault(REMOTE_WRITE_DIR_ENV, Paths::get, Paths.get(Constants.SUITE_ROOT, "remote-write", "remote-write"));
    public static final Path MONITORING_STUFF_DIR = getOrDefault(MONITORING_STUFF_DIR_ENV, Paths::get, Paths.get(Constants.SUITE_ROOT, "kafka-monitoring-stuff", "install"));
    public static final Duration OMB_TEST_DURATION = getOrDefault(OMB_TEST_DURATION_ENV, Duration::parse, Duration.parse("PT1M"));
    public static final Duration OMB_WARMUP_DURATION = getOrDefault(OMB_WARMUP_DURATION_ENV, Duration::parse, Duration.parse("PT1M"));
    public static final Boolean APPLY_BROKER_QUOTA = getOrDefault(APPLY_BROKER_QUOTA_ENV, Boolean::parseBoolean, Boolean.TRUE);

    public static final int MAXIMUM_EXEC_LOG_CHARACTER_SIZE = getOrDefault(STRIMZI_EXEC_MAX_LOG_OUTPUT_CHARACTERS_ENV, Integer::parseInt, 20000);
    public static final boolean OMB_COLLECT_LOG = getOrDefault(OMB_COLLECT_LOG_ENV, Boolean::parseBoolean, false);
    public static final boolean KAFKA_COLLECT_LOG = getOrDefault(KAFKA_COLLECT_LOG_ENV, Boolean::parseBoolean, false);
    public static final int MAX_KAFKA_INSTANCES = getOrDefault(MAX_KAFKA_INSTANCES_ENV, Integer::parseInt, Integer.MAX_VALUE);
    public static final int NUM_INGRESS_CONTROLLERS = getOrDefault(NUM_INGRESS_CONTROLLERS_ENV, Integer::parseInt, 1);
    public static final Path PROVIDED_KAFKA_CLUSTERS_FILE = getOrDefault(PROVIDED_KAFKA_CLUSTERS_FILE_ENV, Paths::get, Paths.get(Constants.SUITE_ROOT, "provided_clusters.yaml"));
    public static final String STRIMZI_VERSION = getOrDefault(STRIMZI_VERSION_ENV, Objects.requireNonNullElse(System.getProperty("strimziVersion"), versionFromMetaInf("io.strimzi/api")));
    public static final Boolean ENABLE_DRAIN_CLEANER = getOrDefault(ENABLE_DRAIN_CLEANER_ENV, Boolean::parseBoolean, Boolean.FALSE);
    public static final int CONSUMER_PER_SUBSCRIPTION = getOrDefault(CONSUMER_PER_SUBSCRIPTION_ENV, Integer::parseInt, 1);
    public static final int TARGET_RATE = getOrDefault(TARGET_RATE_ENV, Integer::parseInt, 2000);
    public static final int WORKERS_PER_INSTANCE = getOrDefault(WORKERS_PER_INSTANCE_ENV, Integer::parseInt, 2);
    public static final int TOPICS_PER_KAFKA = getOrDefault(TOPICS_PER_KAFKA_ENV, Integer::parseInt, 1);
    public static final int PRODUCERS_PER_TOPIC = getOrDefault(PRODUCERS_PER_TOPIC_ENV, Integer::parseInt, 1);
    public static final Quantity PAYLOAD_FILE_SIZE = Quantity.parse(getOrDefault(PAYLOAD_FILE_SIZE_ENV, "1Ki"));

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Help methods
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private Environment() {
    }

    static {
        String debugFormat = "{}: {}";
        LOGGER.info("Used environment variables:");
        LOGGER.info(debugFormat, "CONFIG", config);
        VALUES.forEach((key, value) -> LOGGER.info(debugFormat, key, value));
    }

    static String versionFromMetaInf(String dep) {
        try (InputStream is = Environment.class.getResourceAsStream("/META-INF/maven/" + dep + "/pom.properties")) {
            if (is != null) {
                Properties properties = new Properties();
                properties.load(is);
                return properties.getProperty("version");
            }
        } catch (IOException e) {
        }
        return null;
    }

    private static String getOrDefault(String varName, String defaultValue) {
        return getOrDefault(varName, String::toString, defaultValue);
    }

    private static <T> T getOrDefault(String var, Function<String, T> converter, T defaultValue) {
        String value = System.getenv(var) != null ?
                System.getenv(var) :
                (Objects.requireNonNull(JSON_DATA).get(var) != null ?
                        JSON_DATA.get(var).asText() :
                        null);
        T returnValue = defaultValue;
        if (value != null) {
            returnValue = converter.apply(value);
        }
        VALUES.put(var, String.valueOf(returnValue));
        return returnValue;
    }

    private static JsonNode loadConfigurationFile() {
        config = System.getenv().getOrDefault(CONFIG_FILE_PATH_ENVAR,
                Paths.get(System.getProperty("user.dir"), "config.json").toAbsolutePath().toString());
        ObjectMapper mapper = new ObjectMapper();
        try {
            File jsonFile = new File(config).getAbsoluteFile();
            return mapper.readTree(jsonFile);
        } catch (IOException ex) {
            LOGGER.info("Json configuration not provider or not exists");
            return mapper.createObjectNode();
        }
    }
}
