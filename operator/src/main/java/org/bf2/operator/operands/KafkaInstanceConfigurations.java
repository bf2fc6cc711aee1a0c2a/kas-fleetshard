package org.bf2.operator.operands;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.runtime.Startup;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.config.ConfigProvider;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Loads the properties for each instance profile from config files and from the Quarkus Config.
 * <p>
 * Default properties will have the prefix managedkafka.
 * <p>
 * Properties in each instance file will omit this prefix.
 * <p>
 * Properties overlayed from the Quarkus Config will be prefixed accordingly - managedkafka.kafka... standard.kafka...
 */
@Startup
@ApplicationScoped
public class KafkaInstanceConfigurations {

    private static final String MANAGEDKAFKA = "managedkafka";

    public enum Config {
        STANDARD,
        DEVELOPER
    }

    private Map<Config, KafkaInstanceConfiguration> configs = new HashMap<Config, KafkaInstanceConfiguration>();

    @PostConstruct
    void init() throws IOException {
        // load the default using the managedkafka prefix
        Map<String, String> defaultValues = new KafkaInstanceConfiguration().toMap(true);
        overlayWithConfigProperties(defaultValues, UnaryOperator.identity());

        for (Config config : Config.values()) {
            loadConfiguration(defaultValues, config);
        }
    }

    private void loadConfiguration(Map<String, String> defaultValues, Config toLoad) throws IOException {
        String name = toLoad.name().toLowerCase();
        Map<String, String> allValues = new HashMap<>(defaultValues);

        Properties p = new Properties();
        try (InputStream stream = this.getClass().getResourceAsStream(String.format("/%s.properties", name))) {
            p.load(stream);
        }

        // set the properties from the file
        allValues.keySet().forEach(n -> {
            String key = n.substring(MANAGEDKAFKA.length() + 1);
            String value = p.getProperty(key);
            if (value != null) {
                allValues.put(n, value);
            }
        });

        overlayWithConfigProperties(allValues, n -> n.replace(MANAGEDKAFKA, name));

        KafkaInstanceConfiguration config =
                Serialization.jsonMapper().convertValue(allValues, KafkaInstanceConfiguration.class);
        configs.put(toLoad, config);
    }

    private void overlayWithConfigProperties(Map<String, String> defaultValues, UnaryOperator<String> keyMapper) {
        // overlay anything from the quarkus config
        defaultValues.keySet()
                .forEach(n -> ConfigProvider.getConfig()
                        .getOptionalValue(keyMapper.apply(n), String.class)
                        .ifPresent(v -> defaultValues.put(n, v)));
    }

    public KafkaInstanceConfiguration getConfig(Config config) {
        return configs.get(config);
    }

    public KafkaInstanceConfiguration getConfig(ManagedKafka managedKafka) {
        // TDB where to get name from
        return configs.get(Config.STANDARD);
    }

}
