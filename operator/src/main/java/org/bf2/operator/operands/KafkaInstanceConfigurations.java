package org.bf2.operator.operands;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Infrastructure;
import io.fabric8.openshift.api.model.InfrastructureSpec;
import io.fabric8.openshift.api.model.PlatformSpec;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.runtime.Startup;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.OpenShiftSupport;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;
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
        STANDARD(3),
        DEVELOPER(.1);

        final String lowerName;
        final double nodesPerUnit;

        private InstanceType(double nodesPerUnit) {
            lowerName = name().toLowerCase();
            this.nodesPerUnit = nodesPerUnit;
        }

        public double getNodesPerUnit() {
            return nodesPerUnit;
        }

        public static InstanceType fromLowerName(String name) {
            return InstanceType.valueOf(name.toUpperCase());
        }

        public String getLowerName() {
            return lowerName;
        }
    }

    @Inject
    Config applicationConfig;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    OpenShiftSupport openShiftSupport;

    Map<String, KafkaInstanceConfiguration> configs = new HashMap<>();

    @PostConstruct
    void init() throws IOException {
        // load the default using the managedkafka prefix
        Map<String, String> defaultValues = new KafkaInstanceConfiguration().toMap(true);
        loadConfiguration(defaultValues, MANAGEDKAFKA);

        for (InstanceType type : InstanceType.values()) {
            configs.put(type.lowerName, loadConfiguration(new HashMap<>(defaultValues), type.lowerName));
        }

        if (openShiftSupport.isOpenShift(kubernetesClient)) {
            setDefaultStorageClasses(configs, openShiftSupport.adapt(kubernetesClient));
        }
    }

    void setDefaultStorageClasses(Map<String, KafkaInstanceConfiguration> configs, OpenShiftClient client) {
        Resource<Infrastructure> infraResource = client.config().infrastructures().withName("cluster");

        Optional.ofNullable(infraResource.get())
            .map(Infrastructure::getSpec)
            .map(InfrastructureSpec::getPlatformSpec)
            .map(PlatformSpec::getType)
            .map(String::toLowerCase)
            .map(platformType -> String.format("platform.%s.default-storage-class", platformType))
            .map(applicationConfig::getConfigValue)
            .map(ConfigValue::getValue)
            .filter(Predicate.not(String::isBlank))
            .ifPresent(storageClass ->
                configs.values()
                    .stream()
                    .filter(Predicate.not(this::storageClassSet))
                    .forEach(config -> config.getKafka().setStorageClass(storageClass)));
    }

    boolean storageClassSet(KafkaInstanceConfiguration config) {
        String storageClass = config.getKafka().getStorageClass();
        return storageClass != null && !storageClass.isBlank();
    }

    /* test */ void setApplicationConfig(Config applicationConfig) {
        this.applicationConfig = applicationConfig;
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
                .forEach(e -> applicationConfig
                        .getOptionalValue(keyMapper.apply(e.getKey()), String.class)
                        .ifPresent(v -> e.setValue(v)));
    }

    public KafkaInstanceConfiguration getConfig(InstanceType config) {
        return configs.get(config.lowerName);
    }

    public KafkaInstanceConfiguration getConfig(ManagedKafka managedKafka) {
        return configs.get(getInstanceType(managedKafka));
    }

    public static String getInstanceType(ManagedKafka managedKafka) {
        return OperandUtils.getOrDefault(managedKafka.getMetadata().getLabels(), ManagedKafka.PROFILE_TYPE, InstanceType.STANDARD.lowerName);
    }

}
