package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelector;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelectorBuilder;
import io.fabric8.kubernetes.api.model.PodAffinityTermBuilder;
import io.fabric8.kubernetes.api.model.PodAntiAffinity;
import io.fabric8.kubernetes.api.model.PodAntiAffinityBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.Context;
import io.quarkus.arc.DefaultBean;
import io.strimzi.api.kafka.model.ExternalConfigurationReferenceBuilder;
import io.strimzi.api.kafka.model.ExternalLogging;
import io.strimzi.api.kafka.model.ExternalLoggingBuilder;
import io.strimzi.api.kafka.model.JmxPrometheusExporterMetricsBuilder;
import io.strimzi.api.kafka.model.JvmOptions;
import io.strimzi.api.kafka.model.JvmOptionsBuilder;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaAuthorization;
import io.strimzi.api.kafka.model.KafkaAuthorizationCustomBuilder;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.KafkaExporterSpec;
import io.strimzi.api.kafka.model.KafkaExporterSpecBuilder;
import io.strimzi.api.kafka.model.MetricsConfig;
import io.strimzi.api.kafka.model.Rack;
import io.strimzi.api.kafka.model.RackBuilder;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.api.kafka.model.storage.JbodStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverride;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverrideBuilder;
import io.strimzi.api.kafka.model.storage.SingleVolumeStorage;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplate;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplateBuilder;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplateBuilder;
import io.strimzi.api.kafka.model.template.PodTemplateBuilder;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplate;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplateBuilder;
import org.bf2.common.OperandUtils;
import org.bf2.operator.DrainCleanerManager;
import org.bf2.operator.StrimziManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.secrets.ImagePullSecretManager;
import org.bf2.operator.secrets.SecuritySecretManager;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.xml.bind.DatatypeConverter;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides same functionalities to get a Kafka resource from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@DefaultBean
public class KafkaCluster extends AbstractKafkaCluster {

    // storage related constants
    private static final double HARD_PERCENT = 0.95;
    private static final double SOFT_PERCENT = 0.9;
    private static final String KAFKA_STORAGE_CLASS = "gp2";
    private static final boolean DELETE_CLAIM = true;
    private static final String KAFKA_AUTHORIZER_CLASS = "io.bf2.kafka.authorizer.GlobalAclAuthorizer";
    private static final String KAFKA_AUTHORIZER_CONFIG_PREFIX = "strimzi.authorization.global-authorizer.";
    private static final String KAFKA_AUTHORIZER_CONFIG_ALLOWED_LISTENERS = KAFKA_AUTHORIZER_CONFIG_PREFIX + "allowed-listeners";
    private static final int JBOD_VOLUME_ID = 0;
    private static final Quantity MIN_STORAGE_MARGIN = new Quantity("10Gi");

    private static final Quantity KAFKA_CONTAINER_MEMORY = new Quantity("1Gi");
    private static final Quantity KAFKA_CONTAINER_CPU = new Quantity("1000m");

    private static final Quantity ZOOKEEPER_VOLUME_SIZE = new Quantity("10Gi");
    private static final Quantity ZOOKEEPER_CONTAINER_MEMORY = new Quantity("1Gi");
    private static final Quantity ZOOKEEPER_CONTAINER_CPU = new Quantity("500m");

    private static final Quantity KAFKA_EXPORTER_CONTAINER_MEMORY_REQUEST = new Quantity("128Mi");
    private static final Quantity KAFKA_EXPORTER_CONTAINER_CPU_REQUEST = new Quantity("500m");
    private static final Quantity KAFKA_EXPORTER_CONTAINER_MEMORY_LIMIT = new Quantity("256Mi");
    private static final Quantity KAFKA_EXPORTER_CONTAINER_CPU_LIMIT = new Quantity("1000m");
    private static final String KAFKA_EXPORTER_ENABLE_SARAMA_LOGGING = "enableSaramaLogging";
    private static final String KAFKA_EXPORTER_LOG_LEVEL = "logLevel";

    private static final Integer DEFAULT_CONNECTION_ATTEMPTS_PER_SEC = 100;
    private static final Integer DEFAULT_MAX_CONNECTIONS = 500;
    private static final Quantity DEFAULT_KAFKA_VOLUME_SIZE = new Quantity("1000Gi");
    private static final Quantity DEFAULT_INGRESS_EGRESS_THROUGHPUT_PER_SEC = new Quantity("30Mi");
    private static final Map<String, String> JVM_OPTIONS_XX_MAP = Collections.singletonMap("ExitOnOutOfMemoryError", Boolean.TRUE.toString());
    private static final String DIGEST = "org.bf2.operator/digest";

    @Inject
    Logger log;

    @Inject
    protected SecuritySecretManager secretManager;

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    @Inject
    protected DrainCleanerManager drainCleanerManager;

    @Inject
    protected StrimziManager strimziManager;

    @Inject
    protected StorageClassManager storageClassManager;

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        secretManager.createOrUpdate(managedKafka);

        ConfigMap currentKafkaMetricsConfigMap = cachedConfigMap(managedKafka, kafkaMetricsConfigMapName(managedKafka));
        ConfigMap kafkaMetricsConfigMap = configMapFrom(managedKafka, kafkaMetricsConfigMapName(managedKafka), currentKafkaMetricsConfigMap);
        createOrUpdate(kafkaMetricsConfigMap);

        ConfigMap currentZooKeeperMetricsConfigMap = cachedConfigMap(managedKafka, zookeeperMetricsConfigMapName(managedKafka));
        ConfigMap zooKeeperMetricsConfigMap = configMapFrom(managedKafka, zookeeperMetricsConfigMapName(managedKafka), currentZooKeeperMetricsConfigMap);
        createOrUpdate(zooKeeperMetricsConfigMap);

        // do not reset the kafka logging configuration during the reconcile cycle
        ConfigMap currentKafkaLoggingConfigMap = cachedConfigMap(managedKafka, kafkaLoggingConfigMapName(managedKafka));
        ConfigMap kafkaLoggingConfigMap = configMapFrom(managedKafka, kafkaLoggingConfigMapName(managedKafka), null);
        if (currentKafkaLoggingConfigMap == null || isDigestModified(currentKafkaLoggingConfigMap, kafkaLoggingConfigMap)) {
            createOrUpdate(kafkaLoggingConfigMap);
        }

        // do not reset the exporter logging configuration during the reconcile cycle
        ConfigMap currentKafkaExporterLoggingConfigMap = cachedConfigMap(managedKafka, kafkaExporterLoggingConfigMapName(managedKafka));
        ConfigMap kafkaExporterLoggingConfigMap = configMapFrom(managedKafka, kafkaExporterLoggingConfigMapName(managedKafka), null);
        if (currentKafkaExporterLoggingConfigMap == null || isDigestModified(currentKafkaExporterLoggingConfigMap, kafkaExporterLoggingConfigMap)) {
            createOrUpdate(kafkaExporterLoggingConfigMap);
        }

        // do not reset the zookeeper logging configuration during the reconcile cycle
        ConfigMap currentZookeeperLoggingConfigMap = cachedConfigMap(managedKafka, zookeeperLoggingConfigMapName(managedKafka));
        ConfigMap zookeeperLoggingConfigMap = configMapFrom(managedKafka, zookeeperLoggingConfigMapName(managedKafka), null);
        if (currentZookeeperLoggingConfigMap == null || isDigestModified(currentZookeeperLoggingConfigMap, zookeeperLoggingConfigMap)) {
            createOrUpdate(zookeeperLoggingConfigMap);
        }

        // delete "old" Kafka and ZooKeeper metrics ConfigMaps
        deleteOldMetricsConfigMaps(managedKafka);

        super.createOrUpdate(managedKafka);
    }

    /**
     * Delete "old" Kafka and ZooKeeper metrics ConfigMaps
     * NOTE:
     * Fleetshard 0.0.1 version was using Kafka and ZooKeeper metrics ConfigMaps without Kafka instance name prefixed.
     * Going to delete them, because the new ones are created in the new format.
     *
     * @param managedKafka
     */
    private void deleteOldMetricsConfigMaps(ManagedKafka managedKafka) {
        if (cachedConfigMap(managedKafka, "kafka-metrics") != null) {
            configMapResource(managedKafka, "kafka-metrics").delete();
        }
        if (cachedConfigMap(managedKafka, "zookeeper-metrics") != null) {
            configMapResource(managedKafka, "zookeeper-metrics").delete();
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        super.delete(managedKafka, context);
        secretManager.delete(managedKafka);

        configMapResource(managedKafka, kafkaMetricsConfigMapName(managedKafka)).delete();
        configMapResource(managedKafka, zookeeperMetricsConfigMapName(managedKafka)).delete();
    }

    private void createOrUpdate(ConfigMap configMap) {
        OperandUtils.createOrUpdate(kubernetesClient.configMaps(), configMap);
    }

    /* test */
    @Override
    protected Kafka kafkaFrom(ManagedKafka managedKafka, Kafka current) {
        KafkaBuilder builder = current != null ? new KafkaBuilder(current) : new KafkaBuilder();

        Kafka kafka = builder
                .editOrNewMetadata()
                    .withName(kafkaClusterName(managedKafka))
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withLabels(getKafkaLabels(managedKafka))
                    .withAnnotations(getKafkaAnnotations(managedKafka, current))
                .endMetadata()
                .editOrNewSpec()
                    .editOrNewKafka()
                        .withVersion(managedKafka.getSpec().getVersions().getKafka())
                        .withConfig(getKafkaConfig(managedKafka, current))
                        .withReplicas(KAFKA_BROKERS)
                        .withResources(getKafkaResources(managedKafka))
                        .withJvmOptions(getKafkaJvmOptions(managedKafka))
                        .withStorage(getKafkaStorage(managedKafka, current))
                        .withListeners(getListeners(managedKafka))
                        .withRack(getKafkaRack(managedKafka))
                        .withTemplate(getKafkaTemplate(managedKafka))
                        .withMetricsConfig(getKafkaMetricsConfig(managedKafka))
                        .withAuthorization(getKafkaAuthorization())
                        .withImage(kafkaImage.orElse(null))
                        .withExternalLogging(getKafkaExternalLogging(managedKafka))
                    .endKafka()
                    .editOrNewZookeeper()
                        .withReplicas(ZOOKEEPER_NODES)
                        .withStorage((SingleVolumeStorage)getZooKeeperStorage(current))
                        .withResources(getZooKeeperResources(managedKafka))
                        .withJvmOptions(getZooKeeperJvmOptions(managedKafka))
                        .withTemplate(getZookeeperTemplate(managedKafka))
                        .withMetricsConfig(getZooKeeperMetricsConfig(managedKafka))
                        .withImage(zookeeperImage.orElse(null))
                        .withExternalLogging(getZookeeperExternalLogging(managedKafka))
                    .endZookeeper()
                    .withKafkaExporter(getKafkaExporter(managedKafka))
                .endSpec()
                .build();

        // setting the ManagedKafka as owner of the Kafka resource is needed
        // by the operator sdk to handle events on the Kafka resource properly
        OperandUtils.setAsOwner(managedKafka, kafka);

        return kafka;
    }

    private ConfigMap configMapTemplate(ManagedKafka managedKafka, String name) {
        String templateName = name.substring(managedKafka.getMetadata().getName().length() + 1);

        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(templateName + ".yaml")) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            DigestInputStream dis = new DigestInputStream(is, md);
            ConfigMap template = kubernetesClient.configMaps().load(dis).get();
            Map<String, String> annotations = new HashMap<>(1);
            annotations.put(DIGEST, DatatypeConverter.printHexBinary(md.digest()));
            template.getMetadata().setAnnotations(annotations);
            return template;
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /* test */
    protected ConfigMap configMapFrom(ManagedKafka managedKafka, String name, ConfigMap current) {

        ConfigMap template = configMapTemplate(managedKafka, name);

        ConfigMapBuilder builder = current != null ? new ConfigMapBuilder(current) : new ConfigMapBuilder(template);
        ConfigMap configMap = builder
                .editOrNewMetadata()
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withName(name)
                    .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withData(template.getData())
                .build();

        // setting the ManagedKafka has owner of the ConfigMap resource is needed
        // by the operator sdk to handle events on the ConfigMap resource properly
        OperandUtils.setAsOwner(managedKafka, configMap);

        return configMap;
    }

    private MetricsConfig getKafkaMetricsConfig(ManagedKafka managedKafka) {
        ConfigMapKeySelector cmSelector = new ConfigMapKeySelectorBuilder()
                .withName(kafkaMetricsConfigMapName(managedKafka))
                .withKey("jmx-exporter-config")
                .build();

        return new JmxPrometheusExporterMetricsBuilder()
                .withValueFrom(new ExternalConfigurationReferenceBuilder().withConfigMapKeyRef(cmSelector).build())
                .build();
    }

    private MetricsConfig getZooKeeperMetricsConfig(ManagedKafka managedKafka) {
        ConfigMapKeySelector cmSelector = new ConfigMapKeySelectorBuilder()
                .withName(zookeeperMetricsConfigMapName(managedKafka))
                .withKey("jmx-exporter-config")
                .build();

        return new JmxPrometheusExporterMetricsBuilder()
                .withValueFrom(new ExternalConfigurationReferenceBuilder().withConfigMapKeyRef(cmSelector).build())
                .build();
    }

    private ExternalLogging getZookeeperExternalLogging(ManagedKafka managedKafka) {
        return new ExternalLoggingBuilder()
                .withNewValueFrom()
                    .withNewConfigMapKeyRef("log4j.properties", zookeeperLoggingConfigMapName(managedKafka), false)
                .endValueFrom()
                .build();
    }

    private Rack getKafkaRack(ManagedKafka managedKafka) {
        return new RackBuilder()
                .withNewTopologyKey("topology.kubernetes.io/zone")
                .build();
    }

    private KafkaClusterTemplate getKafkaTemplate(ManagedKafka managedKafka) {
        PodAntiAffinity podAntiAffinity = new PodAntiAffinityBuilder()
                .withRequiredDuringSchedulingIgnoredDuringExecution(
                        new PodAffinityTermBuilder().withTopologyKey("kubernetes.io/hostname").build()
                ).build();

        KafkaClusterTemplateBuilder templateBuilder = new KafkaClusterTemplateBuilder()
                .withPod(new PodTemplateBuilder()
                        .withAffinity(new AffinityBuilder()
                                .withPodAntiAffinity(podAntiAffinity)
                                .build())
                        .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                        .build());

        if (drainCleanerManager.isDrainCleanerWebhookFound()) {
            templateBuilder.withPodDisruptionBudget(
                new PodDisruptionBudgetTemplateBuilder()
                    .withMaxUnavailable(0)
                    .build());
        }

        return templateBuilder.build();
    }

    private ZookeeperClusterTemplate getZookeeperTemplate(ManagedKafka managedKafka) {
        PodAntiAffinity podAntiAffinity = new PodAntiAffinityBuilder()
                .withRequiredDuringSchedulingIgnoredDuringExecution(
                        new PodAffinityTermBuilder().withTopologyKey("kubernetes.io/hostname").build(),
                        new PodAffinityTermBuilder().withTopologyKey("topology.kubernetes.io/zone").build()
                ).build();

        ZookeeperClusterTemplateBuilder templateBuilder = new ZookeeperClusterTemplateBuilder()
                .withPod(new PodTemplateBuilder()
                        .withAffinity(new AffinityBuilder()
                                .withPodAntiAffinity(podAntiAffinity)
                                .build())
                        .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                        .build());

        if (drainCleanerManager.isDrainCleanerWebhookFound()) {
            templateBuilder.withPodDisruptionBudget(
                new PodDisruptionBudgetTemplateBuilder()
                    .withMaxUnavailable(0)
                    .build());
        }

        return templateBuilder.build();
    }

    private JvmOptions getKafkaJvmOptions(ManagedKafka managedKafka) {
        return new JvmOptionsBuilder()
                .withXms("512m")
                .withXmx("512m")
                .withXx(JVM_OPTIONS_XX_MAP)
                .build();
    }

    private JvmOptions getZooKeeperJvmOptions(ManagedKafka managedKafka) {
        return new JvmOptionsBuilder()
                .withXms("512m")
                .withXmx("512m")
                .withXx(JVM_OPTIONS_XX_MAP)
                .build();
    }

    private ResourceRequirements getKafkaResources(ManagedKafka managedKafka) {
        ResourceRequirements resources = new ResourceRequirementsBuilder()
                .addToRequests("memory", KAFKA_CONTAINER_MEMORY)
                .addToRequests("cpu", KAFKA_CONTAINER_CPU)
                .addToLimits("memory", KAFKA_CONTAINER_MEMORY)
                .addToLimits("cpu", KAFKA_CONTAINER_CPU)
                .build();
        return resources;
    }

    private ResourceRequirements getZooKeeperResources(ManagedKafka managedKafka) {
        ResourceRequirements resources = new ResourceRequirementsBuilder()
                .addToRequests("memory", ZOOKEEPER_CONTAINER_MEMORY)
                .addToRequests("cpu", ZOOKEEPER_CONTAINER_CPU)
                .addToLimits("memory", ZOOKEEPER_CONTAINER_MEMORY)
                .addToLimits("cpu", ZOOKEEPER_CONTAINER_CPU)
                .build();
        return resources;
    }

    private KafkaExporterSpec getKafkaExporter(ManagedKafka managedKafka) {
        ConfigMap configMap = cachedConfigMap(managedKafka, kafkaExporterLoggingConfigMapName(managedKafka));
        KafkaExporterSpecBuilder specBuilder = new KafkaExporterSpecBuilder()
                .withTopicRegex(".*")
                .withGroupRegex(".*")
                .withResources(getKafkaExporterResources(managedKafka));

        if (configMap != null) {
            String logLevel = configMap.getData().get(KAFKA_EXPORTER_LOG_LEVEL);
            String saramaLogging = configMap.getData().get(KAFKA_EXPORTER_ENABLE_SARAMA_LOGGING);
            if (logLevel != null && !logLevel.equals("info")) {
                specBuilder.withLogging(logLevel);
            }
            if (Boolean.valueOf(saramaLogging)) {
                specBuilder.withEnableSaramaLogging(true);
            }
        }
        return specBuilder.build();
    }

    private ResourceRequirements getKafkaExporterResources(ManagedKafka managedKafka) {
        ResourceRequirements resources = new ResourceRequirementsBuilder()
                .addToRequests("memory", KAFKA_EXPORTER_CONTAINER_MEMORY_REQUEST)
                .addToRequests("cpu", KAFKA_EXPORTER_CONTAINER_CPU_REQUEST)
                .addToLimits("memory", KAFKA_EXPORTER_CONTAINER_MEMORY_LIMIT)
                .addToLimits("cpu", KAFKA_EXPORTER_CONTAINER_CPU_LIMIT)
                .build();
        return resources;
    }

    private Map<String, Object> getKafkaConfig(ManagedKafka managedKafka, Kafka current) {
        Map<String, Object> config = new HashMap<>();
        config.put("offsets.topic.replication.factor", 3);
        config.put("transaction.state.log.min.isr", 2);
        config.put("transaction.state.log.replication.factor", 3);
        config.put("auto.create.topics.enable", "false");
        config.put("min.insync.replicas", 2);
        config.put("default.replication.factor", 3);
        config.put("log.message.format.version", managedKafka.getSpec().getVersions().getKafka());
        config.put("inter.broker.protocol.version", managedKafka.getSpec().getVersions().getKafka());
        config.put("ssl.enabled.protocols", "TLSv1.3,TLSv1.2");
        config.put("ssl.protocol", "TLS");

        // forcing the preferred leader election as soon as possible
        // NOTE: mostly useful for canary when Kafka brokers roll, partitions move but a preferred leader is not elected
        //       this could be removed,  when we contribute to Sarama to have the support for Elect Leader API
        config.put("leader.imbalance.per.broker.percentage", 0);

        config.put("client.quota.callback.class", "org.apache.kafka.server.quota.StaticQuotaCallback");
        // Throttle at Ingress/Egress MB/sec per broker
        Quantity ingressEgressThroughputPerSec = managedKafka.getSpec().getCapacity().getIngressEgressThroughputPerSec();
        long throughputBytes = (long)(Quantity.getAmountInBytes(Objects.requireNonNullElse(ingressEgressThroughputPerSec, DEFAULT_INGRESS_EGRESS_THROUGHPUT_PER_SEC)).doubleValue() / KAFKA_BROKERS);
        config.put("client.quota.callback.static.produce", String.valueOf(throughputBytes));
        config.put("client.quota.callback.static.consume", String.valueOf(throughputBytes));

        // Start throttling when disk is above 90%. Full stop at 95%.
        Quantity maxDataRetentionSize = getAdjustedMaxDataRetentionSize(managedKafka, current);
        double dataRetentionBytes = Quantity.getAmountInBytes(maxDataRetentionSize).doubleValue();
        config.put("client.quota.callback.static.storage.soft", String.valueOf((long)(SOFT_PERCENT * dataRetentionBytes)));
        config.put("client.quota.callback.static.storage.hard", String.valueOf((long)(HARD_PERCENT * dataRetentionBytes)));

        // Check storage every 30 seconds
        config.put("client.quota.callback.static.storage.check-interval", "30");

        // Anonymous users bypass the quota.  We do this so the Canary is not subjected to the quota checks.
        config.put("client.quota.callback.static.disable-quota-anonymous", Boolean.TRUE.toString());

        config.put("quota.window.num", "30");
        config.put("quota.window.size.seconds", "2");

        // Limit client connections per broker
        Integer totalMaxConnections = managedKafka.getSpec().getCapacity().getTotalMaxConnections();
        config.put("max.connections", String.valueOf((long)(Objects.requireNonNullElse(totalMaxConnections, DEFAULT_MAX_CONNECTIONS) / KAFKA_BROKERS)));
        // Limit connection attempts per broker
        Integer maxConnectionAttemptsPerSec = managedKafka.getSpec().getCapacity().getMaxConnectionAttemptsPerSec();
        config.put("max.connections.creation.rate", String.valueOf(Objects.requireNonNullElse(maxConnectionAttemptsPerSec, DEFAULT_CONNECTION_ATTEMPTS_PER_SEC) / KAFKA_BROKERS));

        // custom authorizer configuration
        addKafkaAuthorizerConfig(config);

        return config;
    }

    private Storage getKafkaStorage(ManagedKafka managedKafka, Kafka current) {
        PersistentClaimStorageBuilder builder = new PersistentClaimStorageBuilder()
                .withId(JBOD_VOLUME_ID)
                .withSize(getAdjustedMaxDataRetentionSize(managedKafka, current).getAmount())
                .withDeleteClaim(DELETE_CLAIM);

        Optional.ofNullable(current).map(k -> k.getSpec()).map(s -> s.getKafka()).map(k -> k.getStorage())
                .map(this::getExistingVolumesFromJbodStorage)
                .ifPresentOrElse(
                        existingVolumes -> existingVolumes.stream().forEach(v -> handleExistingVolume(v, builder, KAFKA_BROKERS)),
                        () -> builder.withOverrides(getStorageOverrides(KAFKA_BROKERS)));

        return new JbodStorageBuilder().withVolumes(builder.build()).build();
    }

    private <S extends Storage> List<SingleVolumeStorage> getExistingVolumesFromJbodStorage(S storage) {
        if (storage instanceof JbodStorage) {
            return ((JbodStorage) storage).getVolumes();
        }
        return null;
    }

    private <V extends SingleVolumeStorage> void handleExistingVolume(V v, PersistentClaimStorageBuilder builder, int numInstances) {
        if (v instanceof PersistentClaimStorage) {
            PersistentClaimStorage persistentClaimStorage = (PersistentClaimStorage) v;
            if (KAFKA_STORAGE_CLASS.equals(persistentClaimStorage.getStorageClass())) {
                log.trace("Not setting storage overrides for pre-existing Kafka with storage class set");
                builder.withStorageClass(KAFKA_STORAGE_CLASS);
            } else if (!persistentClaimStorage.getOverrides().isEmpty()) {
                log.trace("Reusing storage overrides on existing Kafka");
                builder.withOverrides(persistentClaimStorage.getOverrides());
            } else {
                log.trace("Setting per-AZ storage overrides on Kafka");
                builder.withOverrides(getStorageOverrides(numInstances));
            }
        } else {
            log.error("Existing Volume is not an instance of PersistentClaimStorage. This shouldn't happen.");
        }
    }

    private List<PersistentClaimStorageOverride> getStorageOverrides(int num) {
        List<String> storageClasses = storageClassManager.getStorageClassNames();

        List<PersistentClaimStorageOverride> overrides = new ArrayList<>(num);
        for (int i = 0; i < num; i++) {
            overrides.add(
                    new PersistentClaimStorageOverrideBuilder()
                    .withBroker(i)
                    .withStorageClass(storageClasses.get(i % storageClasses.size()))
                    .build());
        }
        return overrides;
    }

    /**
     * Get the effective volume size considering extra padding and the existing size
     */
    private Quantity getAdjustedMaxDataRetentionSize(ManagedKafka managedKafka, Kafka current) {
        Quantity maxDataRetentionSize = managedKafka.getSpec().getCapacity().getMaxDataRetentionSize();
        long bytes;
        if (maxDataRetentionSize == null) {
            bytes = Quantity.getAmountInBytes(DEFAULT_KAFKA_VOLUME_SIZE).longValue();
        } else {
            bytes = Quantity.getAmountInBytes(maxDataRetentionSize).longValue();
        }

        // this is per broker
        bytes /= KAFKA_BROKERS;

        // pad to give a margin before soft/hard limits kick in
        bytes = Math.max(bytes + Quantity.getAmountInBytes(MIN_STORAGE_MARGIN).longValue(), (long) (bytes / SOFT_PERCENT));

        // strimzi won't allow the size to be reduced so scrape the size if possible
        if (current != null) {
            Storage storage = current.getSpec().getKafka().getStorage();
            if (storage instanceof JbodStorage) {
                JbodStorage jbodStorage = (JbodStorage)storage;
                for (SingleVolumeStorage singleVolumeStorage : jbodStorage.getVolumes()) {
                    if (singleVolumeStorage instanceof PersistentClaimStorage && Integer.valueOf(JBOD_VOLUME_ID).equals(singleVolumeStorage.getId())) {
                        String existingSize = ((PersistentClaimStorage)singleVolumeStorage).getSize();
                        long existingBytes = Quantity.getAmountInBytes(Quantity.parse(existingSize)).longValue();
                        // TODO: if not changed a warning may be appropriate, but it would be best as a status condition
                        bytes = Math.max(existingBytes, bytes);
                        break;
                    }
                }
            }
        }

        return new Quantity(String.valueOf(bytes));
    }

    private Storage getZooKeeperStorage(Kafka current) {
        PersistentClaimStorageBuilder builder = new PersistentClaimStorageBuilder()
                .withSize(ZOOKEEPER_VOLUME_SIZE.toString())
                .withDeleteClaim(DELETE_CLAIM);

        Optional.ofNullable(current).map(k -> k.getSpec()).map(s -> s.getZookeeper()).map(z -> z.getStorage())
            .ifPresentOrElse(
                    existing -> handleExistingVolume(existing, builder, ZOOKEEPER_NODES),
                    () -> builder.withOverrides(getStorageOverrides(ZOOKEEPER_NODES)));

        return builder.build();
    }

    private KafkaAuthorization getKafkaAuthorization() {
        return new KafkaAuthorizationCustomBuilder()
                .withAuthorizerClass(KAFKA_AUTHORIZER_CLASS)
                .build();
    }

    private void addKafkaAuthorizerConfig(Map<String, Object> config) {
        config.put(KAFKA_AUTHORIZER_CONFIG_ALLOWED_LISTENERS, "PLAIN-9092,SRE-9096");
        config.put(KAFKA_AUTHORIZER_CONFIG_PREFIX + "acl." + 1, "permission=allow;topic=*;operations=all");
        config.put(KAFKA_AUTHORIZER_CONFIG_PREFIX + "acl." + 2, "permission=allow;group=*;operations=all");
        config.put(KAFKA_AUTHORIZER_CONFIG_PREFIX + "acl." + 3, "permission=allow;transactional_id=*;operations=all");
    }

    private ExternalLogging getKafkaExternalLogging(ManagedKafka managedKafka) {
        return new ExternalLoggingBuilder()
                .withNewValueFrom()
                    .withNewConfigMapKeyRef("log4j.properties", kafkaLoggingConfigMapName(managedKafka), false)
                .endValueFrom()
                .build();
    }

    private Map<String, String> getKafkaLabels(ManagedKafka managedKafka) {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        this.strimziManager.changeStrimziVersion(managedKafka, this, labels);
        labels.put("ingressType", "sharded");
        log.debugf("Kafka %s/%s labels: %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), labels);
        return labels;
    }

    private Map<String, String> getKafkaAnnotations(ManagedKafka managedKafka, Kafka current) {
        Map<String, String> annotations = current != null ? current.getMetadata().getAnnotations() : null;
        if (annotations == null) {
            annotations = new HashMap<>();
        }
        this.strimziManager.togglePauseReconciliation(managedKafka, this, annotations);
        log.debugf("Kafka %s/%s annotations: %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), annotations);
        return annotations;
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = super.isDeleted(managedKafka) &&
                secretManager.isDeleted(managedKafka) &&
                cachedConfigMap(managedKafka, kafkaMetricsConfigMapName(managedKafka)) == null &&
                cachedConfigMap(managedKafka, zookeeperMetricsConfigMapName(managedKafka)) == null;

        log.tracef("KafkaCluster isDeleted = %s", isDeleted);
        return isDeleted;
    }

    private ConfigMap cachedConfigMap(ManagedKafka managedKafka, String name) {
        return informerManager.getLocalConfigMap(kafkaClusterNamespace(managedKafka), name);
    }

    protected Resource<ConfigMap> configMapResource(ManagedKafka managedKafka, String name) {
        return kubernetesClient.configMaps()
                .inNamespace(kafkaClusterNamespace(managedKafka))
                .withName(name);
    }

    public static String kafkaMetricsConfigMapName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-kafka-metrics";
    }

    public static String zookeeperMetricsConfigMapName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-zookeeper-metrics";
    }

    public static String kafkaLoggingConfigMapName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-kafka-logging";
    }

    public static String kafkaExporterLoggingConfigMapName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-kafka-exporter-logging";
    }

    public static String zookeeperLoggingConfigMapName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-zookeeper-logging";
    }

    private boolean isDigestModified(ConfigMap currentCM, ConfigMap newCM) {
        if (currentCM == null || newCM == null) {
            return true;
        }
        String currentDigest = currentCM.getMetadata().getAnnotations() == null ? null : currentCM.getMetadata().getAnnotations().get(DIGEST);
        String newDigest = newCM.getMetadata().getAnnotations() == null ? null : newCM.getMetadata().getAnnotations().get(DIGEST);
        return !Objects.equals(currentDigest, newDigest);
    }
}
