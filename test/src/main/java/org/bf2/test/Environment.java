package org.bf2.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Class which holds environment variables for system tests.
 */
public class Environment {

    private static final Logger LOGGER = LogManager.getLogger(Environment.class);
    private static final Map<String, String> VALUES = new HashMap<>();
    private static final JsonNode JSON_DATA = loadConfigurationFile();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm");
    private static String config;

    /*
     * Definition of env vars
     */
    private static final String LOG_DIR_ENV = "LOG_DIR";
    private static final String CONFIG_FILE_PATH_ENV = "CONFIG_PATH";
    private static final String YAML_BUNDLE_PATH_ENV = "YAML_BUNDLE_PATH";
    private static final String FLEET_SHARD_OPERATOR_IMAGE_ENV = "FLEET_SHARD_OPERATOR_IMAGE";


    /*
     * Setup constants from env variables or set default
     */
    public static final String SUITE_ROOT = System.getProperty("user.dir");
    public static final Path LOG_DIR = getOrDefault(LOG_DIR_ENV, Paths::get, Paths.get(SUITE_ROOT, "target", "logs")).resolve("test-run-" + DATE_FORMAT.format(LocalDateTime.now()));
    public static final Path ROOT_PATH = Paths.get(System.getProperty("user.dir")).getParent();
    public static final Path YAML_BUNDLE_PATH = getOrDefault(YAML_BUNDLE_PATH_ENV, Paths::get, Paths.get(ROOT_PATH.toString(), "kas-fleetshard-operator", "src", "main", "kubernetes"));
    public static final Path CRD_PATH = ROOT_PATH.resolve("kas-fleetshard-api").resolve("target").resolve("classes").resolve("META-INF").resolve("fabric8").resolve("managedkafkas.managedkafka.bf2.org-v1.yml");
    public static final String FLEET_SHARD_IMAGE = getOrDefault(FLEET_SHARD_OPERATOR_IMAGE_ENV, "localhost:5000/bf2/kas-fleetshard-operator:latest");

    private Environment() {
    }

    static {
        String debugFormat = "{}: {}";
        LOGGER.info("=======================================================================");
        LOGGER.info("Used environment variables:");
        LOGGER.info(debugFormat, "CONFIG", config);
        VALUES.forEach((key, value) -> LOGGER.info(debugFormat, key, value));
        LOGGER.info("=======================================================================");
    }

    /**
     * Get value from env or  from config or default and parse it to String data type
     *
     * @param varName      variable name
     * @param defaultValue default string value
     * @return value of variable
     */
    private static String getOrDefault(String varName, String defaultValue) {
        return getOrDefault(varName, String::toString, defaultValue);
    }

    /**
     * Get value from env or  from config or default and parse it to defined type
     *
     * @param var          env variable name
     * @param converter    converter from string to defined type
     * @param defaultValue default value if variable is not set in env or config
     * @return value of variable fin defined data type
     */
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

    /**
     * Load configuration fom config file
     *
     * @return json object with loaded variables
     */
    private static JsonNode loadConfigurationFile() {
        config = System.getenv().getOrDefault(CONFIG_FILE_PATH_ENV,
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
