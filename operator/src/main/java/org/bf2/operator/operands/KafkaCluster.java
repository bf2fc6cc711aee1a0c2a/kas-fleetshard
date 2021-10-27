package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelector;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelectorBuilder;
import io.fabric8.kubernetes.api.model.PodAffinity;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
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
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.DrainCleanerManager;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.managers.IngressControllerManager;
import org.bf2.operator.managers.SecuritySecretManager;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.operands.KafkaInstanceConfiguration.AccessControl;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
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

/**
 * Provides same functionalities to get a Kafka resource from a ManagedKafka one
 * and checking the corresponding status
 */
@Startup
@ApplicationScoped
@DefaultBean
public class KafkaCluster extends AbstractKafkaCluster {

    // storage related constants
    private static final double HARD_PERCENT = 0.95;
    private static final double SOFT_PERCENT = 0.9;

    private static final boolean DELETE_CLAIM = true;
    private static final int JBOD_VOLUME_ID = 0;
    private static final Quantity MIN_STORAGE_MARGIN = new Quantity("10Gi");

    private static final String KAFKA_EXPORTER_ENABLE_SARAMA_LOGGING = "enableSaramaLogging";
    private static final String KAFKA_EXPORTER_LOG_LEVEL = "logLevel";

    private static final String DIGEST = "org.bf2.operator/digest";
    private static final String IO_STRIMZI_KAFKA_QUOTA_STATIC_QUOTA_CALLBACK = "io.strimzi.kafka.quotas.StaticQuotaCallback";

    private static final String SERVICE_ACCOUNT_KEY = "managedkafka.kafka.acl.service-accounts.%s";

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
    protected Instance<IngressControllerManager> ingressControllerManagerInstance;

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
    public Kafka kafkaFrom(ManagedKafka managedKafka, Kafka current) {
        KafkaBuilder builder = current != null ? new KafkaBuilder(current) : new KafkaBuilder();

        Kafka kafka = builder
                .editOrNewMetadata()
                    .withName(kafkaClusterName(managedKafka))
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withLabels(buildKafkaLabels(managedKafka))
                    .withAnnotations(buildKafkaAnnotations(managedKafka, current))
                .endMetadata()
                .editOrNewSpec()
                    .editOrNewKafka()
                        .withVersion(managedKafka.getSpec().getVersions().getKafka())
                        .withConfig(buildKafkaConfig(managedKafka, current))
                        .withReplicas(this.config.getKafka().getReplicas())
                        .withResources(buildKafkaResources(managedKafka))
                        .withJvmOptions(buildKafkaJvmOptions(managedKafka))
                        .withStorage(buildKafkaStorage(managedKafka, current))
                        .withListeners(buildListeners(managedKafka))
                        .withRack(buildKafkaRack(managedKafka))
                        .withTemplate(buildKafkaTemplate(managedKafka))
                        .withMetricsConfig(buildKafkaMetricsConfig(managedKafka))
                        .withAuthorization(buildKafkaAuthorization(managedKafka))
                        .withImage(kafkaImage.orElse(null))
                        .withExternalLogging(buildKafkaExternalLogging(managedKafka))
                    .endKafka()
                    .editOrNewZookeeper()
                        .withReplicas(this.config.getZookeeper().getReplicas())
                        .withStorage((SingleVolumeStorage) buildZooKeeperStorage(current))
                        .withResources(buildZooKeeperResources(managedKafka))
                        .withJvmOptions(buildZooKeeperJvmOptions(managedKafka))
                        .withTemplate(buildZookeeperTemplate(managedKafka))
                        .withMetricsConfig(buildZooKeeperMetricsConfig(managedKafka))
                        .withImage(zookeeperImage.orElse(null))
                        .withExternalLogging(buildZookeeperExternalLogging(managedKafka))
                    .endZookeeper()
                    .withKafkaExporter(buildKafkaExporter(managedKafka))
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
                .withNewTopologyKey("topology.kubernetes.io/zone")
                .build();
    }

    private KafkaClusterTemplate buildKafkaTemplate(ManagedKafka managedKafka) {
        AffinityBuilder affinityBuilder = new AffinityBuilder();

        boolean addAffinity = false;
        if (this.config.getKafka().isColocateWithZookeeper()) {
            // adds preference to co-locate Kafka broker pods with ZK pods with same cluster label
            PodAffinity zkPodAffinity = OperandUtils.buildZookeeperPodAffinity(managedKafka).getPodAffinity();
            affinityBuilder.withPodAffinity(zkPodAffinity);
            addAffinity = true;
        }

        // ensures even distribution of the Kafka pods in a given cluster across the availability zones
        // the previous affinity make sure single per node or not
        // this only comes into picture when there are more number of nodes than the brokers
        PodTemplateBuilder podTemplateBuilder = new PodTemplateBuilder()
                .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                .withTopologySpreadConstraints(azAwareTopologySpreadConstraint(managedKafka.getMetadata().getName() + "-kafka", "DoNotSchedule"));

        if (addAffinity) {
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

    private ZookeeperClusterTemplate buildZookeeperTemplate(ManagedKafka managedKafka) {
        // should enforce 1 per node, but cannot because the pod labels are namespaced and
        // each managed kafka is in it's own namespace
        // we need kubernetes 1.22 to address this an empty anti-affinity namespaceselector

        // use "ScheduleAnyway" here due to fact that any previous ZK instances may not have been correctly AZ aware
        // and StatefulSet can not be moved with across zone with current setup
        ZookeeperClusterTemplateBuilder templateBuilder = new ZookeeperClusterTemplateBuilder()
                .withPod(new PodTemplateBuilder()
                        .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                        .withTopologySpreadConstraints(azAwareTopologySpreadConstraint(managedKafka.getMetadata().getName() + "-zookeeper", "ScheduleAnyway"))
                        .build());

        if (drainCleanerManager.isDrainCleanerWebhookFound()) {
            templateBuilder.withPodDisruptionBudget(
                new PodDisruptionBudgetTemplateBuilder()
                    .withMaxUnavailable(0)
                    .build());
        }

        return templateBuilder.build();
    }

    private JvmOptions buildKafkaJvmOptions(ManagedKafka managedKafka) {
        return new JvmOptionsBuilder()
                .withXms(this.config.getKafka().getJvmXms())
                .withXmx(this.config.getKafka().getJvmXms())
                .withXx(this.config.getKafka().getJvmXxMap())
                .build();
    }

    private JvmOptions buildZooKeeperJvmOptions(ManagedKafka managedKafka) {
        return new JvmOptionsBuilder()
                .withXms(this.config.getZookeeper().getJvmXms())
                .withXmx(this.config.getZookeeper().getJvmXms())
                .withXx(this.config.getZookeeper().getJvmXxMap())
                .build();
    }

    private ResourceRequirements buildKafkaResources(ManagedKafka managedKafka) {
        return new ResourceRequirementsBuilder()
                .addToRequests("memory", new Quantity(this.config.getKafka().getContainerMemory()))
                .addToRequests("cpu", new Quantity(this.config.getKafka().getContainerCpu()))
                .addToLimits("memory", new Quantity(this.config.getKafka().getContainerMemory()))
                .addToLimits("cpu", new Quantity(this.config.getKafka().getContainerCpu()))
                .build();
    }

    private ResourceRequirements buildZooKeeperResources(ManagedKafka managedKafka) {
        return new ResourceRequirementsBuilder()
                .addToRequests("memory", new Quantity(this.config.getZookeeper().getContainerMemory()))
                .addToRequests("cpu", new Quantity(this.config.getZookeeper().getContainerCpu()))
                .addToLimits("memory", new Quantity(this.config.getZookeeper().getContainerMemory()))
                .addToLimits("cpu", new Quantity(this.config.getZookeeper().getContainerCpu()))
                .build();
    }

    private KafkaExporterSpec buildKafkaExporter(ManagedKafka managedKafka) {
        ConfigMap configMap = cachedConfigMap(managedKafka, kafkaExporterLoggingConfigMapName(managedKafka));
        KafkaExporterSpecBuilder specBuilder = new KafkaExporterSpecBuilder()
                .withTopicRegex(".*")
                .withGroupRegex(".*")
                .withResources(buildKafkaExporterResources(managedKafka));

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

        if(this.config.getExporter().isColocateWithZookeeper()) {
            specBuilder
                .editOrNewTemplate()
                    .editOrNewPod()
                        .withAffinity(OperandUtils.buildZookeeperPodAffinity(managedKafka))
                    .endPod()
                .endTemplate();
        }
        return specBuilder.build();
    }

    private ResourceRequirements buildKafkaExporterResources(ManagedKafka managedKafka) {
        return new ResourceRequirementsBuilder()
                .addToRequests("memory", new Quantity(this.config.getExporter().getContainerRequestMemory()))
                .addToRequests("cpu", new Quantity(this.config.getExporter().getContainerRequestCpu()))
                .addToLimits("memory", new Quantity(this.config.getExporter().getContainerMemory()))
                .addToLimits("cpu", new Quantity(this.config.getExporter().getContainerCpu()))
                .build();
    }

    private Map<String, Object> buildKafkaConfig(ManagedKafka managedKafka, Kafka current) {
        Map<String, Object> config = new HashMap<>();
        config.put("offsets.topic.replication.factor", 3);
        config.put("transaction.state.log.min.isr", 2);
        config.put("transaction.state.log.replication.factor", 3);
        config.put("auto.create.topics.enable", "false");
        config.put("min.insync.replicas", 2);
        config.put("default.replication.factor", 3);
        config.put("log.message.format.version", managedKafka.getSpec().getVersions().getKafka());
        config.put("inter.broker.protocol.version",
                managedKafka.getSpec().getVersions().getKafkaIbp() != null ?
                managedKafka.getSpec().getVersions().getKafkaIbp() :
                managedKafka.getSpec().getVersions().getKafka());
        config.put("ssl.enabled.protocols", "TLSv1.3,TLSv1.2");
        config.put("ssl.protocol", "TLS");

        var maximumSessionLifetime = managedKafka.getSpec().getOauth().getMaximumSessionLifetime();
        long maxReauthMs = maximumSessionLifetime != null ?
                Math.max(maximumSessionLifetime, 0) :
                this.config.getKafka().getMaximumSessionLifetimeDefault();
        config.put("connections.max.reauth.ms", maxReauthMs);

        if (managedKafka.getSpec().getVersions().compareStrimziVersionTo(Versions.STRIMZI_CLUSTER_OPERATOR_V0_23_0_4) >= 0) {
            // extension to manage the create topic to ensure valid Replication Factor and ISR
            config.put("create.topic.policy.class.name", "io.bf2.kafka.topic.ManagedKafkaCreateTopicPolicy");
        }

        // forcing the preferred leader election as soon as possible
        // NOTE: mostly useful for canary when Kafka brokers roll, partitions move but a preferred leader is not elected
        //       this could be removed,  when we contribute to Sarama to have the support for Elect Leader API
        config.put("leader.imbalance.per.broker.percentage", 0);

        // configure quota plugin
        if (this.config.getKafka().isEnableQuota()) {
            addQuotaConfig(managedKafka, current, config);
        }

        // custom authorizer configuration
        addKafkaAuthorizerConfig(managedKafka, config);

        return config;
    }

    private void addQuotaConfig(ManagedKafka managedKafka, Kafka current, Map<String, Object> config) {

        config.put("client.quota.callback.class", IO_STRIMZI_KAFKA_QUOTA_STATIC_QUOTA_CALLBACK);

        // Throttle at Ingress/Egress MB/sec per broker
        Quantity ingressEgressThroughputPerSec = managedKafka.getSpec().getCapacity().getIngressEgressThroughputPerSec();
        long throughputBytes = (long)(Quantity.getAmountInBytes(Objects.requireNonNullElse(ingressEgressThroughputPerSec, new Quantity(this.config.getKafka().getIngressThroughputPerSec()))).doubleValue() / this.config.getKafka().getReplicas());
        config.put("client.quota.callback.static.produce", String.valueOf(throughputBytes));
        config.put("client.quota.callback.static.fetch", String.valueOf(throughputBytes));

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
    }

    private Storage buildKafkaStorage(ManagedKafka managedKafka, Kafka current) {
        PersistentClaimStorageBuilder builder = new PersistentClaimStorageBuilder()
                .withId(JBOD_VOLUME_ID)
                .withSize(getAdjustedMaxDataRetentionSize(managedKafka, current).getAmount())
                .withDeleteClaim(DELETE_CLAIM);

        Optional.ofNullable(current).map(Kafka::getSpec).map(KafkaSpec::getKafka).map(KafkaClusterSpec::getStorage)
                .map(this::getExistingVolumesFromJbodStorage)
                .ifPresentOrElse(
                        existingVolumes -> existingVolumes.stream().forEach(v -> handleExistingVolume(v, builder)),
                        () -> builder.withStorageClass(config.getKafka().getStorageClass()));

        return new JbodStorageBuilder().withVolumes(builder.build()).build();
    }

    private <S extends Storage> List<SingleVolumeStorage> getExistingVolumesFromJbodStorage(S storage) {
        if (storage instanceof JbodStorage) {
            return ((JbodStorage) storage).getVolumes();
        }
        return null;
    }

    private <V extends SingleVolumeStorage> void handleExistingVolume(V v, PersistentClaimStorageBuilder builder) {
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
     * Get the effective volume size considering extra padding and the existing size
     */
    private Quantity getAdjustedMaxDataRetentionSize(ManagedKafka managedKafka, Kafka current) {
        Quantity maxDataRetentionSize = managedKafka.getSpec().getCapacity().getMaxDataRetentionSize();
        long bytes;
        if (maxDataRetentionSize == null) {
            bytes = Quantity.getAmountInBytes(new Quantity(this.config.getKafka().getVolumeSize())).longValue();
        } else {
            bytes = Quantity.getAmountInBytes(maxDataRetentionSize).longValue();
        }

        // this is per broker
        bytes /= this.config.getKafka().getReplicas();

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

    private Storage buildZooKeeperStorage(Kafka current) {
        PersistentClaimStorageBuilder builder = new PersistentClaimStorageBuilder()
                .withSize(config.getZookeeper().getVolumeSize())
                .withDeleteClaim(DELETE_CLAIM);

        Optional.ofNullable(current).map(Kafka::getSpec).map(KafkaSpec::getZookeeper).map(ZookeeperClusterSpec::getStorage)
            .ifPresentOrElse(
                    existing -> handleExistingVolume(existing, builder),
                    () -> builder.withStorageClass(config.getKafka().getStorageClass()));

        return builder.build();
    }

    private AccessControl getAclConfig(ManagedKafka managedKafka) {
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
        final String aclKeyTemplate = configPrefix + "acl.%03d";
        final String aclLoggingKeyTemplate = configPrefix + "acl.logging.%03d";

        // Deprecated option: Remove when canary, must-gather, and SRE are configured via ManagedKafka CR
        if (aclConfig.allowedListeners != null) {
            config.put(allowedListenersKey, aclConfig.allowedListeners);
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
        this.strimziManager.changeStrimziVersion(managedKafka, this, labels);
        labels.put("ingressType", "sharded");

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

    /* test */
    protected KafkaInstanceConfiguration getKafkaConfiguration() {
        return this.config;
    }

    /* test */
    protected void setKafkaConfiguration(KafkaInstanceConfiguration config) {
        this.config = config;
    }
}
