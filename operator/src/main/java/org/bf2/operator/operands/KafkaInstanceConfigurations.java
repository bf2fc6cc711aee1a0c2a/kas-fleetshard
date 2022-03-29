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
 * Properties overlaid from the Quarkus Config will be prefixed accordingly - managedkafka.kafka... standard.kafka...
 */
@Startup
@ApplicationScoped
public class KafkaInstanceConfigurations {

    private static final String MANAGEDKAFKA = "managedkafka";

    public enum InstanceType {
        STANDARD,
        DEVELOPER
    }

    private Map<InstanceType, KafkaInstanceConfiguration> configs = new HashMap<InstanceType, KafkaInstanceConfiguration>();

    @PostConstruct
    void init() throws IOException {
        // load the default using the managedkafka prefix
        Map<String, String> defaultValues = new KafkaInstanceConfiguration().toMap(true);
        loadConfiguration(defaultValues, MANAGEDKAFKA);

        for (InstanceType type : InstanceType.values()) {
            configs.put(type, loadConfiguration(new HashMap<>(defaultValues), type.name().toLowerCase()));
        }
    }

    private KafkaInstanceConfiguration loadConfiguration(Map<String, String> allValues, String name) throws IOException {
        Properties p = new Properties();
        try (InputStream stream = this.getClass().getResourceAsStream(String.format("/instances/%s.properties", name))) {
            p.load(stream);
        }

        // set the properties from the file
        allValues.entrySet().forEach(e -> {
            String key = e.getKey().substring(MANAGEDKAFKA.length() + 1);
            String value = p.getProperty(key);
            if (value != null) {
                e.setValue(value);
            }
        });

        overlayWithConfigProperties(allValues, n -> n.replace(MANAGEDKAFKA, name));

        return Serialization.jsonMapper().convertValue(allValues, KafkaInstanceConfiguration.class);
    }

    private void overlayWithConfigProperties(Map<String, String> defaultValues, UnaryOperator<String> keyMapper) {
        // overlay anything from the quarkus config
        defaultValues.entrySet()
                .forEach(e -> ConfigProvider.getConfig()
                        .getOptionalValue(keyMapper.apply(e.getKey()), String.class)
                        .ifPresent(v -> e.setValue(v)));
    }

    public KafkaInstanceConfiguration getConfig(InstanceType config) {
        return configs.get(config);
    }

    public KafkaInstanceConfiguration getConfig(ManagedKafka managedKafka) {
        // TDB where to get name from
        return configs.get(InstanceType.STANDARD);
    }

}
