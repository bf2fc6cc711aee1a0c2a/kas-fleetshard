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
import org.bf2.operator.managers.OperandOverrideManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.jboss.logging.Logger;

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

    public enum Platform {
        AWS,
        GCP
    }

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
    Logger log;

    @Inject
    Config applicationConfig;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    OpenShiftSupport openShiftSupport;

    @Inject
    OperandOverrideManager overrideManager;

    Map<String, KafkaInstanceConfiguration> configs = new HashMap<>();

    KafkaInstanceConfiguration standardDynamic;

    private Platform platform = Platform.AWS;

    @PostConstruct
    void init() throws IOException {
        // load the default using the managedkafka prefix
        Map<String, String> defaultValues = new KafkaInstanceConfiguration().toMap(true);
        loadConfiguration(defaultValues, MANAGEDKAFKA);

        for (InstanceType type : InstanceType.values()) {
            configs.put(type.lowerName, loadConfiguration(new HashMap<>(defaultValues), type.lowerName));
        }

        KafkaInstanceConfiguration standard = configs.get(InstanceType.STANDARD.lowerName);
        standardDynamic = loadConfiguration(standard.toMap(true), "standard-dynamic");

        if (openShiftSupport.isOpenShift(kubernetesClient)) {
            OpenShiftClient client = openShiftSupport.adapt(kubernetesClient);
            Resource<Infrastructure> infraResource = client.config().infrastructures().withName("cluster");

            Optional<String> infraPlatform = Optional.ofNullable(infraResource.get())
                .map(Infrastructure::getSpec)
                .map(InfrastructureSpec::getPlatformSpec)
                .map(PlatformSpec::getType);

            try {
                infraPlatform.map(Platform::valueOf).ifPresent(p -> this.platform = p);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown platform type, defaulting to AWS", e);
            }

            setDefaultStorageClasses(configs, infraPlatform);
        }
    }

    void setDefaultStorageClasses(Map<String, KafkaInstanceConfiguration> configs, Optional<String> platform) {
        platform.map(String::toLowerCase)
            .map(platformType -> String.format("platform.%s.default-storage-class", platformType))
            .map(applicationConfig::getConfigValue)
            .map(ConfigValue::getValue)
            .filter(Predicate.not(String::isBlank))
            .ifPresent(storageClass -> {
                configs.values()
                    .stream()
                    .filter(Predicate.not(this::storageClassSet))
                    .forEach(config -> config.getKafka().setStorageClass(storageClass));

                if (!storageClassSet(standardDynamic)) {
                    standardDynamic.getKafka().setStorageClass(storageClass);
                }
            });
    }

    public Platform getPlatform() {
        return platform;
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
        String instanceType = getInstanceType(managedKafka);
        if (InstanceType.STANDARD.lowerName.equals(instanceType) &&
                overrideManager.useDynamicScalingScheduling(managedKafka.getSpec().getVersions().getStrimzi())) {
            return standardDynamic;
        }
        return configs.get(instanceType);
    }

    public static String getInstanceType(ManagedKafka managedKafka) {
        return OperandUtils.getOrDefault(managedKafka.getMetadata().getLabels(), ManagedKafka.PROFILE_TYPE, InstanceType.STANDARD.lowerName);
    }

}
