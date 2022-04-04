package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelector;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelectorBuilder;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimStatus;
import io.fabric8.kubernetes.api.model.PodAffinity;
import io.fabric8.kubernetes.api.model.PodAffinityTerm;
import io.fabric8.kubernetes.api.model.PodAffinityTermBuilder;
import io.fabric8.kubernetes.api.model.PodAntiAffinity;
import io.fabric8.kubernetes.api.model.PodAntiAffinityBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.api.model.TopologySpreadConstraint;
import io.fabric8.kubernetes.api.model.TopologySpreadConstraintBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.Context;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.Startup;
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
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaExporterSpec;
import io.strimzi.api.kafka.model.KafkaExporterSpecBuilder;
import io.strimzi.api.kafka.model.KafkaSpec;
import io.strimzi.api.kafka.model.MetricsConfig;
import io.strimzi.api.kafka.model.Rack;
import io.strimzi.api.kafka.model.RackBuilder;
import io.strimzi.api.kafka.model.SystemProperty;
import io.strimzi.api.kafka.model.SystemPropertyBuilder;
import io.strimzi.api.kafka.model.ZookeeperClusterSpec;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.api.kafka.model.storage.JbodStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.storage.SingleVolumeStorage;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplate;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplateBuilder;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplateBuilder;
import io.strimzi.api.kafka.model.template.PodTemplateBuilder;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplate;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplateBuilder;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplateFluent.PodNested;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.DrainCleanerManager;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.managers.IngressControllerManager;
import org.bf2.operator.managers.KafkaManager;
import org.bf2.operator.managers.SecuritySecretManager;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.operands.KafkaInstanceConfiguration.AccessControl;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuth;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;
import org.bf2.operator.resources.v1alpha1.Versions;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.xml.bind.DatatypeConverter;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Provides same functionalities to get a Kafka resource from a ManagedKafka one
 * and checking the corresponding status
 */
@Startup
@ApplicationScoped
@DefaultBean
public class KafkaCluster extends AbstractKafkaCluster {

    private static final String QUOTA_FETCH = "client.quota.callback.static.fetch";

    private static final String QUOTA_PRODUCE = "client.quota.callback.static.produce";

    private static final String DO_NOT_SCHEDULE = "DoNotSchedule";

    private static final boolean DELETE_CLAIM = true;
    private static final int JBOD_VOLUME_ID = 0;

    private static final String KAFKA_EXPORTER_ENABLE_SARAMA_LOGGING = "enableSaramaLogging";
    private static final String KAFKA_EXPORTER_LOG_LEVEL = "logLevel";

    private static final String DIGEST = "org.bf2.operator/digest";
    /* tracks the number of brokers desired as inferred from the capacity
     * it may differ from the actual number of brokers on the kafka resource
     * and will later need a reconciliation process
     */
    private static final String REPLICAS = "org.bf2.operator/desired-broker-replicas";
    private static final String IO_STRIMZI_KAFKA_QUOTA_STATIC_QUOTA_CALLBACK = "io.strimzi.kafka.quotas.StaticQuotaCallback";

    private static final String SERVICE_ACCOUNT_KEY = "managedkafka.kafka.acl.service-accounts.%s";

    public static final String MAX_PARTITIONS = "max.partitions";
    public static final String MESSAGE_MAX_BYTES = "message.max.bytes";

    @Inject
    Logger log;

    @Inject
    Config applicationConfig;

    @Inject
    protected SecuritySecretManager secretManager;

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    @Inject
    protected DrainCleanerManager drainCleanerManager;

    @Inject
    protected StrimziManager strimziManager;

    @Inject
    protected KafkaManager kafkaManager;

    @Inject
    protected Instance<IngressControllerManager> ingressControllerManagerInstance;

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        secretManager.createOrUpdate(managedKafka);

        ConfigMap currentKafkaMetricsConfigMap = cachedConfigMap(managedKafka, kafkaMetricsConfigMapName(managedKafka));
        ConfigMap kafkaMetricsConfigMap = configMapFrom(managedKafka, kafkaMetricsConfigMapName(managedKafka));
        createOrUpdateIfNecessary(currentKafkaMetricsConfigMap, kafkaMetricsConfigMap);

        ConfigMap currentZooKeeperMetricsConfigMap = cachedConfigMap(managedKafka, zookeeperMetricsConfigMapName(managedKafka));
        ConfigMap zooKeeperMetricsConfigMap = configMapFrom(managedKafka, zookeeperMetricsConfigMapName(managedKafka));
        createOrUpdateIfNecessary(currentZooKeeperMetricsConfigMap, zooKeeperMetricsConfigMap);

        ConfigMap currentKafkaLoggingConfigMap = cachedConfigMap(managedKafka, kafkaLoggingConfigMapName(managedKafka));
        ConfigMap kafkaLoggingConfigMap = configMapFrom(managedKafka, kafkaLoggingConfigMapName(managedKafka));
        createOrUpdateIfNecessary(currentKafkaLoggingConfigMap, kafkaLoggingConfigMap);

        ConfigMap currentKafkaExporterLoggingConfigMap = cachedConfigMap(managedKafka, kafkaExporterLoggingConfigMapName(managedKafka));
        ConfigMap kafkaExporterLoggingConfigMap = configMapFrom(managedKafka, kafkaExporterLoggingConfigMapName(managedKafka));
        createOrUpdateIfNecessary(currentKafkaExporterLoggingConfigMap, kafkaExporterLoggingConfigMap);

        ConfigMap currentZookeeperLoggingConfigMap = cachedConfigMap(managedKafka, zookeeperLoggingConfigMapName(managedKafka));
        ConfigMap zookeeperLoggingConfigMap = configMapFrom(managedKafka, zookeeperLoggingConfigMapName(managedKafka));
        createOrUpdateIfNecessary(currentZookeeperLoggingConfigMap, zookeeperLoggingConfigMap);

        super.createOrUpdate(managedKafka);
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
    public Kafka kafkaFrom(ManagedKafka managedKafka, Kafka current) {
        KafkaBuilder builder = current != null ? new KafkaBuilder(current) : new KafkaBuilder();

        int actualReplicas = getBrokerReplicas(managedKafka, current);
        int desiredReplicas = getBrokerReplicas(managedKafka, null);

        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        KafkaBuilder kafkaBuilder = builder
                .editOrNewMetadata()
                    .withName(kafkaClusterName(managedKafka))
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withLabels(buildKafkaLabels(managedKafka))
                    .withAnnotations(buildKafkaAnnotations(managedKafka, current))
                    .addToAnnotations(REPLICAS, String.valueOf(desiredReplicas))
                .endMetadata()
                .editOrNewSpec()
                    .editOrNewKafka()
                        .withVersion(this.kafkaManager.currentKafkaVersion(managedKafka))
                        .withConfig(buildKafkaConfig(managedKafka, current))
                        .withReplicas(actualReplicas)
                        .withResources(config.kafka.buildResources())
                        .withJvmOptions(buildKafkaJvmOptions(managedKafka))
                        .withStorage(buildKafkaStorage(managedKafka, current))
                        .withListeners(buildListeners(managedKafka, actualReplicas))
                        .withRack(buildKafkaRack(managedKafka))
                        .withTemplate(buildKafkaTemplate(managedKafka))
                        .withMetricsConfig(buildKafkaMetricsConfig(managedKafka))
                        .withAuthorization(buildKafkaAuthorization(managedKafka))
                        .withImage(kafkaImage.orElse(null))
                        .withExternalLogging(buildKafkaExternalLogging(managedKafka))
                    .endKafka()
                    .editOrNewZookeeper()
                        .withReplicas(config.getZookeeper().getReplicas())
                        .withStorage((SingleVolumeStorage) buildZooKeeperStorage(current, config))
                        .withResources(config.zookeeper.buildResources())
                        .withJvmOptions(buildZooKeeperJvmOptions(managedKafka))
                        .withTemplate(buildZookeeperTemplate(managedKafka))
                        .withMetricsConfig(buildZooKeeperMetricsConfig(managedKafka))
                        .withImage(zookeeperImage.orElse(null))
                        .withExternalLogging(buildZookeeperExternalLogging(managedKafka))
                    .endZookeeper()
                    .withKafkaExporter(buildKafkaExporter(managedKafka))
                .endSpec();

        Kafka kafka = this.upgrade(managedKafka, kafkaBuilder);

        // setting the ManagedKafka as owner of the Kafka resource is needed
        // by the operator sdk to handle events on the Kafka resource properly
        OperandUtils.setAsOwner(managedKafka, kafka);

        return kafka;
    }

    @Override
    public int getReplicas(ManagedKafka managedKafka) {
        return getBrokerReplicas(managedKafka, cachedKafka(managedKafka));
    }

    public int getBrokerReplicas(ManagedKafka managedKafka, Kafka current) {
        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        Integer replicas = config.getKafka().getReplicasOverride();
        if (replicas != null) {
            return replicas;
        }
        if (current != null) {
            return current.getSpec().getKafka().getReplicas();
        }
        Integer maxPartitions = managedKafka.getSpec().getCapacity().getMaxPartitions();
        int scalingAndReplicationFactor = config.getKafka().getScalingAndReplicationFactor();
        // assume 1 physical unit if no other information
        int desiredReplicas = scalingAndReplicationFactor;
        if (maxPartitions != null) {
            double physicalCapacity = config.getKafka().getPartitionCapacity();
            desiredReplicas = (int) Math.ceil(maxPartitions * scalingAndReplicationFactor / physicalCapacity);
            // round to only even multiples
            desiredReplicas = (int) (Math.ceil(desiredReplicas / (double)scalingAndReplicationFactor) * scalingAndReplicationFactor);
        }

        return desiredReplicas;
    }

    /**
     * Update the Kafka custom resource if any kind of upgrade has to run
     * If no upgrade has to be done, it just builds and return the current Kafka custom resource
     *
     * @param managedKafka ManagedKafka instance
     * @param kafkaBuilder Kafka builder to update the corresponding Kafka custom resource
     * @return the updated Kafka custom resource with changes related to upgrade
     */
    private Kafka upgrade(ManagedKafka managedKafka, KafkaBuilder kafkaBuilder) {
        if (this.strimziManager.hasStrimziChanged(managedKafka)
                || StrimziManager.isPauseReasonStrimziUpdate(kafkaBuilder.buildMetadata().getAnnotations())) {
            log.infof("Strimzi version upgrade ...");
            this.strimziManager.upgradeStrimziVersion(managedKafka, this, kafkaBuilder);
        } else if (this.kafkaManager.hasKafkaVersionChanged(managedKafka)) {
            log.infof("Kafka version upgrade ...");
            this.kafkaManager.upgradeKafkaVersion(managedKafka, kafkaBuilder);
        } else if (!this.kafkaManager.isKafkaUpgradeInProgress(managedKafka, this)) {
            if (this.kafkaManager.isKafkaUpgradeStabilityCheckToRun(managedKafka, this)) {
                log.infof("Kafka version upgrade stability check ...");
                this.kafkaManager.checkKafkaUpgradeIsStable(managedKafka);
            } else if (!this.kafkaManager.isKafkaUpgradeStabilityCheckInProgress(managedKafka, this) && this.kafkaManager.hasKafkaIbpVersionChanged(managedKafka)) {
                log.infof("Kafka IBP version upgrade ...");
                this.kafkaManager.upgradeKafkaIbpVersion(managedKafka, kafkaBuilder);
            }
        }
        return kafkaBuilder.build();
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
    protected ConfigMap configMapFrom(ManagedKafka managedKafka, String name) {

        ConfigMap template = configMapTemplate(managedKafka, name);

        ConfigMapBuilder builder = new ConfigMapBuilder(template);
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

    private MetricsConfig buildKafkaMetricsConfig(ManagedKafka managedKafka) {
        ConfigMapKeySelector cmSelector = new ConfigMapKeySelectorBuilder()
                .withName(kafkaMetricsConfigMapName(managedKafka))
                .withKey("jmx-exporter-config")
                .build();

        return new JmxPrometheusExporterMetricsBuilder()
                .withValueFrom(new ExternalConfigurationReferenceBuilder().withConfigMapKeyRef(cmSelector).build())
                .build();
    }

    private MetricsConfig buildZooKeeperMetricsConfig(ManagedKafka managedKafka) {
        ConfigMapKeySelector cmSelector = new ConfigMapKeySelectorBuilder()
                .withName(zookeeperMetricsConfigMapName(managedKafka))
                .withKey("jmx-exporter-config")
                .build();

        return new JmxPrometheusExporterMetricsBuilder()
                .withValueFrom(new ExternalConfigurationReferenceBuilder().withConfigMapKeyRef(cmSelector).build())
                .build();
    }

    private ExternalLogging buildZookeeperExternalLogging(ManagedKafka managedKafka) {
        return new ExternalLoggingBuilder()
                .withNewValueFrom()
                    .withNewConfigMapKeyRef("log4j.properties", zookeeperLoggingConfigMapName(managedKafka), false)
                .endValueFrom()
                .build();
    }

    private Rack buildKafkaRack(ManagedKafka managedKafka) {
        return new RackBuilder()
                .withTopologyKey("topology.kubernetes.io/zone")
                .build();
    }

    private KafkaClusterTemplate buildKafkaTemplate(ManagedKafka managedKafka) {
        AffinityBuilder affinityBuilder = new AffinityBuilder();

        // ensures even distribution of the Kafka pods in a given cluster across the availability zones
        // the previous affinity make sure single per node or not
        // this only comes into picture when there are more number of nodes than the brokers
        PodTemplateBuilder podTemplateBuilder = new PodTemplateBuilder()
                .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                .withTopologySpreadConstraints(azAwareTopologySpreadConstraint(managedKafka.getMetadata().getName() + "-kafka", DO_NOT_SCHEDULE));

        if (this.configs.getConfig(managedKafka).getKafka().isColocateWithZookeeper()) {
            // adds preference to co-locate Kafka broker pods with ZK pods with same cluster label
            PodAffinity zkPodAffinity = OperandUtils.buildZookeeperPodAffinity(managedKafka).getPodAffinity();
            affinityBuilder.withPodAffinity(zkPodAffinity);
            podTemplateBuilder.withAffinity(affinityBuilder.build());
        }

        // add toleration on broker pod such that it can be placed on specific worker nodes
        // note that the affinity/topology stuff make sure they are evenly spread across
        // the availability zone and worker nodes, but all worker nodes are same as
        // some of them will have ZK, admin-server, canary and broker needs to be on its own
        podTemplateBuilder.withTolerations(buildKafkaBrokerToleration());

        KafkaClusterTemplateBuilder templateBuilder = new KafkaClusterTemplateBuilder()
                .withPod(podTemplateBuilder.build());

        if (drainCleanerManager.isDrainCleanerWebhookFound()) {
            templateBuilder.withPodDisruptionBudget(
                new PodDisruptionBudgetTemplateBuilder()
                    .withMaxUnavailable(0)
                    .build());
        }

        return templateBuilder.build();
    }

    public static Toleration buildKafkaBrokerToleration() {
        return new TolerationBuilder()
                .withKey("org.bf2.operator/kafka-broker")
                .withOperator("Exists")
                .withEffect("NoExecute")
                .build();
    }

    private TopologySpreadConstraint azAwareTopologySpreadConstraint(String instanceName, String action) {
        return new TopologySpreadConstraintBuilder()
                .withMaxSkew(1)
                .withTopologyKey(IngressControllerManager.TOPOLOGY_KEY)
                .withNewLabelSelector()
                    .addNewMatchExpression()
                        .withKey("strimzi.io/name")
                        .withValues(instanceName)
                        .withOperator("In")
                    .endMatchExpression()
                .endLabelSelector()
                .withWhenUnsatisfiable(action)
                .build();
    }

    private PodAffinityTerm affinityTerm(String key, String value) {
        return new PodAffinityTermBuilder()
                .withTopologyKey("kubernetes.io/hostname")
                .withNewLabelSelector()
                .withMatchLabels(Map.of(key, value))
                .endLabelSelector().build();
    }

    private ZookeeperClusterTemplate buildZookeeperTemplate(ManagedKafka managedKafka) {
        // onePerNode = true - one zk per node exclusively
        // onePerNode = false - one zk per node per managedkafka
        boolean onePerNode = this.configs.getConfig(managedKafka).getKafka().isOneInstancePerNode();

        PodNested<ZookeeperClusterTemplateBuilder> podNestedBuilder = new ZookeeperClusterTemplateBuilder()
                .withNewPod()
                        .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                        .withTopologySpreadConstraints(azAwareTopologySpreadConstraint(managedKafka.getMetadata().getName() + "-zookeeper", DO_NOT_SCHEDULE));

        if (onePerNode) {
            PodAffinityTerm affinityTerm = affinityTerm("app.kubernetes.io/name", "zookeeper");
            affinityTerm.setNamespaceSelector(new LabelSelector());

            PodAntiAffinity podAntiAffinity = new PodAntiAffinityBuilder()
                    .withRequiredDuringSchedulingIgnoredDuringExecution(affinityTerm)
                    .build();

            AffinityBuilder affinityBuilder = new AffinityBuilder();
            affinityBuilder.withPodAntiAffinity(podAntiAffinity);

            podNestedBuilder.withAffinity(affinityBuilder.build());
        }


        ZookeeperClusterTemplateBuilder templateBuilder = podNestedBuilder.endPod();

        if (drainCleanerManager.isDrainCleanerWebhookFound()) {
            templateBuilder.withPodDisruptionBudget(
                new PodDisruptionBudgetTemplateBuilder()
                    .withMaxUnavailable(0)
                    .build());
        }

        return templateBuilder.build();
    }

    private JvmOptions buildKafkaJvmOptions(ManagedKafka managedKafka) {
        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        return new JvmOptionsBuilder()
                .withXms(config.getKafka().getJvmXms())
                .withXmx(config.getKafka().getJvmXms())
                .withXx(config.getKafka().getJvmXxMap())
                .withJavaSystemProperties(buildJavaSystemProperties())
                .build();
    }

    private List<SystemProperty> buildJavaSystemProperties() {
        return List.of(
                new SystemPropertyBuilder().withName("com.sun.management.jmxremote.port").withValue("9999").build(),
                new SystemPropertyBuilder().withName("com.sun.management.jmxremote.host").withValue("127.0.0.1").build(),
                new SystemPropertyBuilder().withName("java.rmi.server.hostname").withValue("127.0.0.1").build());
    }

    private JvmOptions buildZooKeeperJvmOptions(ManagedKafka managedKafka) {
        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        return new JvmOptionsBuilder()
                .withXms(config.getZookeeper().getJvmXms())
                .withXmx(config.getZookeeper().getJvmXms())
                .withXx(config.getZookeeper().getJvmXxMap())
                .build();
    }

    private KafkaExporterSpec buildKafkaExporter(ManagedKafka managedKafka) {
        ConfigMap configMap = cachedConfigMap(managedKafka, kafkaExporterLoggingConfigMapName(managedKafka));
        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        KafkaExporterSpecBuilder specBuilder = new KafkaExporterSpecBuilder()
                .withTopicRegex(".*")
                .withGroupRegex(".*")
                .withResources(config.getExporter().buildResources());

        if (configMap != null) {
            String logLevel = configMap.getData().get(KAFKA_EXPORTER_LOG_LEVEL);
            String saramaLogging = configMap.getData().get(KAFKA_EXPORTER_ENABLE_SARAMA_LOGGING);
            if (!"info".equals(logLevel)) {
                specBuilder.withLogging(logLevel);
            }
            if (Boolean.valueOf(saramaLogging)) {
                specBuilder.withEnableSaramaLogging(true);
            }
        }

        if(config.getExporter().isColocateWithZookeeper()) {
            specBuilder
                .editOrNewTemplate()
                    .editOrNewPod()
                        .withAffinity(OperandUtils.buildZookeeperPodAffinity(managedKafka))
                    .endPod()
                .endTemplate();
        }
        return specBuilder.build();
    }

    private Map<String, Object> buildKafkaConfig(ManagedKafka managedKafka, Kafka current) {
        Map<String, Object> config = new HashMap<>();
        KafkaInstanceConfiguration instanceConfig = this.configs.getConfig(managedKafka);
        int scalingAndReplicationFactor = instanceConfig.getKafka().getScalingAndReplicationFactor();
        config.put("offsets.topic.replication.factor", scalingAndReplicationFactor);
        config.put("transaction.state.log.min.isr", Math.min(scalingAndReplicationFactor, 2));
        config.put("transaction.state.log.replication.factor", scalingAndReplicationFactor);
        config.put("auto.create.topics.enable", "false");
        config.put("min.insync.replicas", Math.min(scalingAndReplicationFactor, 2));
        config.put("default.replication.factor", scalingAndReplicationFactor);
        config.put("log.message.format.version", this.kafkaManager.currentKafkaLogMessageFormatVersion(managedKafka));
        config.put("inter.broker.protocol.version", this.kafkaManager.currentKafkaIbpVersion(managedKafka));
        config.put("ssl.enabled.protocols", "TLSv1.3,TLSv1.2");
        config.put("ssl.protocol", "TLS");

        ManagedKafkaAuthenticationOAuth oauth = managedKafka.getSpec().getOauth();
        var maximumSessionLifetime = oauth != null ? oauth.getMaximumSessionLifetime() : null;
        long maxReauthMs = maximumSessionLifetime != null ?
                Math.max(maximumSessionLifetime, 0) :
                    instanceConfig.getKafka().getMaximumSessionLifetimeDefault();
        config.put("connections.max.reauth.ms", maxReauthMs);

        if (managedKafka.getSpec().getVersions().compareStrimziVersionTo(Versions.STRIMZI_CLUSTER_OPERATOR_V0_23_0_4) >= 0) {
            // extension to manage the create topic to ensure valid Replication Factor and ISR
            config.put("create.topic.policy.class.name", "io.bf2.kafka.topic.ManagedKafkaCreateTopicPolicy");
        }

        // forcing the preferred leader election as soon as possible
        // NOTE: mostly useful for canary when Kafka brokers roll, partitions move but a preferred leader is not elected
        //       this could be removed,  when we contribute to Sarama to have the support for Elect Leader API
        config.put("leader.imbalance.per.broker.percentage", 0);

        config.put(MESSAGE_MAX_BYTES, instanceConfig.getKafka().getMessageMaxBytes());

        // configure quota plugin
        if (instanceConfig.getKafka().isEnableQuota()) {
            addQuotaConfig(managedKafka, current, config);
        }

        // custom authorizer configuration
        addKafkaAuthorizerConfig(managedKafka, config);

        if (managedKafka.getSpec().getCapacity().getMaxPartitions() != null) {
            config.put(MAX_PARTITIONS, managedKafka.getSpec().getCapacity().getMaxPartitions());
        }

        config.put("strimzi.authorization.custom-authorizer.partition-counter.timeout-seconds", 10);
        config.put("strimzi.authorization.custom-authorizer.partition-counter.schedule-interval-seconds", 15);
        config.put("strimzi.authorization.custom-authorizer.partition-counter.private-topic-prefix",
                instanceConfig.kafka.acl.privatePrefix);

        config.put("strimzi.authorization.custom-authorizer.adminclient-listener.name", "controlplane-9090");
        config.put("strimzi.authorization.custom-authorizer.adminclient-listener.port", 9090);
        config.put("strimzi.authorization.custom-authorizer.adminclient-listener.protocol", "SSL");

        return config;
    }

    public static String getProduceQuota(Kafka kafka) {
        return (String)kafka.getSpec().getKafka().getConfig().get(QUOTA_PRODUCE);
    }

    public static String getFetchQuota(Kafka kafka) {
        return (String)kafka.getSpec().getKafka().getConfig().get(QUOTA_FETCH);
    }

    private void addQuotaConfig(ManagedKafka managedKafka, Kafka current, Map<String, Object> config) {

        config.put("client.quota.callback.class", IO_STRIMZI_KAFKA_QUOTA_STATIC_QUOTA_CALLBACK);

        // Throttle at Ingress/Egress MB/sec per broker
        config.put(QUOTA_PRODUCE, String.valueOf(getIngressBytes(managedKafka, current)));
        config.put(QUOTA_FETCH, String.valueOf(getEgressBytes(managedKafka, current)));

        // Start throttling when disk is above requested size. Full stop when only storageMinMargin is free.
        final long maxRetentionBytes = Quantity.getAmountInBytes(getAdjustedMaxDataRetentionSize(managedKafka, current)).longValue();
        final long storagePaddingBytes = getStoragePadding(managedKafka, current);
        long storageLimit = maxRetentionBytes - storagePaddingBytes;
        //For RHOASK its unlikely that customers will notice producer throttling, so we set it to the same as the hard limit and let the hard limit win
        config.put("client.quota.callback.static.storage.soft", String.valueOf(storageLimit));
        config.put("client.quota.callback.static.storage.hard", String.valueOf(storageLimit));

        // Check storage every storageCheckInterval seconds
        KafkaInstanceConfiguration instanceConfig = this.configs.getConfig(managedKafka);
        config.put("client.quota.callback.static.storage.check-interval", String.valueOf(instanceConfig.getStorage().getCheckInterval()));

        // Configure the quota plugin so that the canary is not subjected to the quota checks.
        Optional<ServiceAccount> canaryServiceAccount = managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary);
        canaryServiceAccount.ifPresent(serviceAccount -> config.put("client.quota.callback.static.excluded.principal.name.list", serviceAccount.getPrincipal()));

        config.put("quota.window.num", "30");
        config.put("quota.window.size.seconds", "2");
    }

    private Storage buildKafkaStorage(ManagedKafka managedKafka, Kafka current) {
        PersistentClaimStorageBuilder builder = new PersistentClaimStorageBuilder()
                .withId(JBOD_VOLUME_ID)
                .withSize(getAdjustedMaxDataRetentionSize(managedKafka, current).getAmount())
                .withDeleteClaim(DELETE_CLAIM);

        Optional.ofNullable(current).map(Kafka::getSpec).map(KafkaSpec::getKafka).map(KafkaClusterSpec::getStorage)
                .map(this::getExistingVolumesFromJbodStorage)
                .ifPresentOrElse(
                        existingVolumes -> existingVolumes.stream().forEach(v -> handleExistingVolume(v, builder, this.configs.getConfig(managedKafka))),
                        () -> builder.withStorageClass(this.configs.getConfig(managedKafka).getKafka().getStorageClass()));

        return new JbodStorageBuilder().withVolumes(builder.build()).build();
    }

    private <S extends Storage> List<SingleVolumeStorage> getExistingVolumesFromJbodStorage(S storage) {
        if (storage instanceof JbodStorage) {
            return ((JbodStorage) storage).getVolumes();
        }
        return null;
    }

    private <V extends SingleVolumeStorage> void handleExistingVolume(V v, PersistentClaimStorageBuilder builder, KafkaInstanceConfiguration config) {
        if (v instanceof PersistentClaimStorage) {
            PersistentClaimStorage persistentClaimStorage = (PersistentClaimStorage) v;
            if (persistentClaimStorage.getOverrides() != null && !persistentClaimStorage.getOverrides().isEmpty()) {
                log.trace("Reusing storage overrides on existing Kafka");
                builder.withOverrides(persistentClaimStorage.getOverrides());
            } else {
                log.trace("Setting default StorageClass on Kafka");
                builder.withStorageClass(config.getKafka().getStorageClass());
            }
        } else {
            log.error("Existing Volume is not an instance of PersistentClaimStorage. This shouldn't happen.");
        }
    }

    /**
     * Get per broker value
     */
    private long getPerBrokerBytes(ManagedKafka managedKafka, Kafka current, Quantity quantity, Supplier<String> defaultValue) {
        long bytes = Quantity.getAmountInBytes(Optional.ofNullable(quantity).orElseGet(() -> Quantity.parse(defaultValue.get()))).longValue();

        return bytes / getBrokerReplicas(managedKafka, current);
    }

    private long getIngressBytes(ManagedKafka managedKafka, Kafka current) {
        return getPerBrokerBytes(managedKafka, current, managedKafka.getSpec().getCapacity().getIngressPerSec(), () -> this.configs.getConfig(managedKafka).getKafka().getIngressPerSec());
    }

    private long getEgressBytes(ManagedKafka managedKafka, Kafka current) {
        return getPerBrokerBytes(managedKafka, current, managedKafka.getSpec().getCapacity().getEgressPerSec(), () -> this.configs.getConfig(managedKafka).getKafka().getEgressPerSec());
    }

    /**
     * Get extra storage padding given the effective IngressEgressThroughput limit and storageMinMargin
     */
    private long getStoragePadding(ManagedKafka managedKafka, Kafka current) {
        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        return Quantity.getAmountInBytes(config.getStorage().getMinMargin()).longValue()
                + getIngressBytes(managedKafka, current) * config.getStorage().getCheckInterval()
                        * config.getStorage().getSafetyFactor();
    }

    /**
     * Get the effective volume size considering extra padding and the existing size
     */
    private Quantity getAdjustedMaxDataRetentionSize(ManagedKafka managedKafka, Kafka current) {
        long bytes = getPerBrokerBytes(managedKafka, current, managedKafka.getSpec().getCapacity().getMaxDataRetentionSize(), () -> this.configs.getConfig(managedKafka).getKafka().getVolumeSize());

        // pad to give a margin before soft/hard limits kick in
        bytes += getStoragePadding(managedKafka, current);

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

    public long unpadBrokerStorage(ManagedKafka managedKafka, Kafka current, long value) {
        return value - getStoragePadding(managedKafka, current);
    }

    /**
     * Get the current sum of storage as reported by the pvcs.
     * This may not match the requested amount ephemerally, or due to rounding
     */
    @Override
    public Quantity calculateRetentionSize(ManagedKafka managedKafka) {
        Kafka current = cachedKafka(managedKafka);
        long storageInGbs = informerManager.getPvcsInNamespace(managedKafka.getMetadata().getNamespace()).stream().map(pvc -> {
            if (pvc.getStatus() == null) {
                return 0L;
            }
            PersistentVolumeClaimStatus status = pvc.getStatus();
            Quantity q = OperandUtils.getOrDefault(status.getCapacity(), "storage", (Quantity)null);
            if (q == null) {
                return 0L;
            }
            long value = Quantity.getAmountInBytes(q).longValue();
            // round down to the nearest GB - the PVC request is automatically rounded up
            return (long)Math.floor(((double)unpadBrokerStorage(managedKafka, current, value))/(1L<<30));
        }).collect(Collectors.summingLong(Long::longValue));

        Quantity capacity = managedKafka.getSpec().getCapacity().getMaxDataRetentionSize();

        // try to correct for the overall rounding
        if (storageInGbs > 0 && (capacity == null
                || ("Gi".equals(capacity.getFormat()) && (Quantity.getAmountInBytes(capacity).longValue() / (1L << 30))
                        % getBrokerReplicas(managedKafka, current) != 0))) {
            storageInGbs++;
        }

        return Quantity.parse(String.format("%sGi",storageInGbs));
    }

    private Storage buildZooKeeperStorage(Kafka current, KafkaInstanceConfiguration config) {
        PersistentClaimStorageBuilder builder = new PersistentClaimStorageBuilder()
                .withSize(config.getZookeeper().getVolumeSize())
                .withDeleteClaim(DELETE_CLAIM);

        Optional.ofNullable(current).map(Kafka::getSpec).map(KafkaSpec::getZookeeper).map(ZookeeperClusterSpec::getStorage)
            .ifPresentOrElse(
                    existing -> handleExistingVolume(existing, builder, config),
                    () -> builder.withStorageClass(config.getKafka().getStorageClass()));

        return builder.build();
    }

    private AccessControl getAclConfig(ManagedKafka managedKafka) {
        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        AccessControl legacyConfig = config.getKafka().getAclLegacy();

        if (legacyConfig != null && managedKafka.getSpec().getVersions().compareStrimziVersionTo(legacyConfig.getFinalVersion()) <= 0) {
            /*
             * Use legacy configuration when present and the Kafka Strimzi version is less than
             * or equal to the final version given for legacy.
             * */
            return legacyConfig;
        }

        return config.getKafka().getAcl();
    }

    private KafkaAuthorization buildKafkaAuthorization(ManagedKafka managedKafka) {
        return new KafkaAuthorizationCustomBuilder()
                .withAuthorizerClass(getAclConfig(managedKafka).getAuthorizerClass())
                .build();
    }

    private void addKafkaAuthorizerConfig(ManagedKafka managedKafka, Map<String, Object> config) {
        List<String> owners = managedKafka.getSpec().getOwners();
        AtomicInteger aclCount = new AtomicInteger(0);
        AtomicInteger aclLoggingCount = new AtomicInteger(0);
        AccessControl aclConfig = getAclConfig(managedKafka);

        final String configPrefix = aclConfig.getConfigPrefix();
        final String allowedListenersKey = configPrefix + "allowed-listeners";
        final String resourceOperationsKey = configPrefix + "resource-operations";
        final String aclKeyPrefix = configPrefix + "acl";
        final String aclLoggingKeyPrefix = aclKeyPrefix + ".logging";
        final String aclKeyTemplate = aclKeyPrefix + ".%03d";
        final String aclLoggingKeyTemplate = aclLoggingKeyPrefix + ".%03d";

        // Deprecated option: Remove when canary, must-gather, and SRE are configured via ManagedKafka CR
        if (aclConfig.allowedListeners != null) {
            config.put(allowedListenersKey, aclConfig.allowedListeners);
        }

        if (aclConfig.getLoggingSuppressionWindow() != null) {
            String key = aclLoggingKeyPrefix + ".suppressionWindow";
            if (aclConfig.getLoggingSuppressionWindow().getDuration() != null) {
                config.put(key + ".duration", aclConfig.getLoggingSuppressionWindow().getDuration());
            }
            if (aclConfig.getLoggingSuppressionWindow().getApis() != null) {
                config.put(key + ".apis", aclConfig.getLoggingSuppressionWindow().getApis());
            }
            if (aclConfig.getLoggingSuppressionWindow().getEventCount() != null) {
                config.put(key + ".eventCount", aclConfig.getLoggingSuppressionWindow().getEventCount());
            }
        }

        addAcl(aclConfig.getGlobal(), "", aclKeyTemplate, aclCount, config);
        addAcl(aclConfig.getLogging(), "", aclLoggingKeyTemplate, aclLoggingCount, config);

        config.put(resourceOperationsKey, aclConfig.getResourceOperations());

        for (String owner : owners) {
            addAcl(aclConfig.getOwner(), owner, aclKeyTemplate, aclCount, config);
        }

        Objects.requireNonNullElse(managedKafka.getSpec().getServiceAccounts(), Collections.<ServiceAccount>emptyList())
            .stream()
            .forEach(account -> {
                String aclKey = String.format(SERVICE_ACCOUNT_KEY, account.getName());

                applicationConfig.getOptionalValue(aclKey, String.class)
                    .ifPresent(acl -> addAcl(acl, account.getPrincipal(), aclKeyTemplate, aclCount, config));
            });
    }

    private void addAcl(String configuredAcl, String principal, String keyTemplate, AtomicInteger aclCount, Map<String, Object> config) {
        if (configuredAcl != null) {
            (principal.isEmpty() ? configuredAcl : String.format(configuredAcl, principal))
                .lines()
                .map(String::trim)
                .forEach(entry -> config.put(String.format(keyTemplate, aclCount.incrementAndGet()), entry));
        }
    }

    private ExternalLogging buildKafkaExternalLogging(ManagedKafka managedKafka) {
        return new ExternalLoggingBuilder()
                .withNewValueFrom()
                    .withNewConfigMapKeyRef("log4j.properties", kafkaLoggingConfigMapName(managedKafka), false)
                .endValueFrom()
                .build();
    }

    private Map<String, String> buildKafkaLabels(ManagedKafka managedKafka) {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        //this.strimziManager.changeStrimziVersion(managedKafka, this, labels);
        labels.put("ingressType", "sharded");
        labels.put(this.strimziManager.getVersionLabel(), this.strimziManager.currentStrimziVersion(managedKafka));

        if (ingressControllerManagerInstance.isResolvable()) {
            labels.putAll(ingressControllerManagerInstance.get().getRouteMatchLabels());
        }

        log.debugf("Kafka %s/%s labels: %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), labels);
        return labels;
    }

    private Map<String, String> buildKafkaAnnotations(ManagedKafka managedKafka, Kafka current) {
        Map<String, String> annotations = current != null ? current.getMetadata().getAnnotations() : null;
        if (annotations == null) {
            annotations = new HashMap<>();
        }
        //this.strimziManager.togglePauseReconciliation(managedKafka, this, annotations);
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

    /**
     * Allow local modifications to the configmap to remain until such time a new release provides a new configmap
     * (which will have a differing digest).
     */
    private void createOrUpdateIfNecessary(ConfigMap currentCM, ConfigMap newCM) {
        if (currentCM == null || isDigestModified(currentCM, newCM)) {
            createOrUpdate(newCM);
        }
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
