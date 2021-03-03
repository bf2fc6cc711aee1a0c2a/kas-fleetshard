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
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.Context;
import io.strimzi.api.kafka.model.CertAndKeySecretSource;
import io.strimzi.api.kafka.model.CertAndKeySecretSourceBuilder;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.CertSecretSourceBuilder;
import io.strimzi.api.kafka.model.ExternalConfigurationReferenceBuilder;
import io.strimzi.api.kafka.model.GenericSecretSource;
import io.strimzi.api.kafka.model.GenericSecretSourceBuilder;
import io.strimzi.api.kafka.model.JmxPrometheusExporterMetricsBuilder;
import io.strimzi.api.kafka.model.JvmOptions;
import io.strimzi.api.kafka.model.JvmOptionsBuilder;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.MetricsConfig;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthentication;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationOAuthBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListeners;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListenersBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBootstrapBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBroker;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBrokerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.storage.JbodStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.storage.SingleVolumeStorage;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplate;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplateBuilder;
import io.strimzi.api.kafka.model.template.PodTemplateBuilder;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplate;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplateBuilder;
import org.bf2.operator.InformerManager;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuth;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides same functionalities to get a Kafka resource from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
public class KafkaCluster implements Operand<ManagedKafka> {

    private static final Logger log = LoggerFactory.getLogger(KafkaCluster.class);

    private static final int KAFKA_BROKERS = 3;
    private static final int ZOOKEEPER_NODES = 3;
    private static final int PRODUCE_QUOTA = 4000000;
    private static final int CONSUME_QUOTA = 4000000;
    private static final String KAFKA_STORAGE_CLASS = "mk-storageclass";
    private static final boolean DELETE_CLAIM = false;
    private static final int JBOD_VOLUME_ID = 0;

    private static final Quantity KAFKA_VOLUME_SIZE = new Quantity("225Gi");
    private static final Quantity KAFKA_CONTAINER_MEMORY = new Quantity("1Gi");
    private static final Quantity KAFKA_CONTAINER_CPU = new Quantity("1000m");

    private static final Quantity ZOOKEEPER_VOLUME_SIZE = new Quantity("10Gi");
    private static final Quantity ZOOKEEPER_CONTAINER_MEMORY = new Quantity("1Gi");
    private static final Quantity ZOOKEEPER_CONTAINER_CPU = new Quantity("500m");

    private static final Map<String, String> MANAGED_BY_LABELS = Collections.singletonMap("app.kubernetes.io/managed-by", "kas-fleetshard-operator");

    @Inject
    KafkaResourceClient kafkaResourceClient;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    InformerManager informerManager;

    @ConfigProperty(name = "kafka.authentication.enabled", defaultValue = "false")
    boolean isKafkaAuthenticationEnabled;
    @ConfigProperty(name = "kafka.external.certificate.enabled", defaultValue = "false")
    boolean isKafkaExternalCertificateEnabled;

    Base64.Encoder encoder = Base64.getEncoder();

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {

        if (isKafkaExternalCertificateEnabled) {
            Secret currentTlsSecret = cachedSecret(managedKafka, kafkaTlsSecretName(managedKafka));
            Secret tlsSecret = tlsSecretFrom(managedKafka, currentTlsSecret);
            createOrUpdate(tlsSecret);
        }

        if (isKafkaAuthenticationEnabled) {
            Secret currentSsoClientSecret = cachedSecret(managedKafka, ssoClientSecretName(managedKafka));
            Secret ssoClientSecret = ssoClientSecretFrom(managedKafka, currentSsoClientSecret);
            createOrUpdate(ssoClientSecret);

            Secret currentSsoTlsSecret = cachedSecret(managedKafka, ssoTlsSecretName(managedKafka));
            Secret ssoTlsSecret = ssoTlsSecretFrom(managedKafka, currentSsoTlsSecret);
            createOrUpdate(ssoTlsSecret);
        }

        ConfigMap currentKafkaMetricsConfigMap = cachedConfigMap(managedKafka, kafkaMetricsConfigMapName(managedKafka));
        ConfigMap kafkaMetricsConfigMap = configMapFrom(managedKafka, kafkaMetricsConfigMapName(managedKafka), currentKafkaMetricsConfigMap);
        createOrUpdate(kafkaMetricsConfigMap);

        ConfigMap currentZooKeeperMetricsConfigMap = cachedConfigMap(managedKafka, zookeeperMetricsConfigMapName(managedKafka));
        ConfigMap zooKeeperMetricsConfigMap = configMapFrom(managedKafka, zookeeperMetricsConfigMapName(managedKafka), currentZooKeeperMetricsConfigMap);
        createOrUpdate(zooKeeperMetricsConfigMap);

        Kafka currentKafka = cachedDeployment(managedKafka);
        Kafka kafka = kafkaFrom(managedKafka, currentKafka);
        createOrUpdate(kafka);
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kafkaResourceClient.delete(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
        kubernetesClient.configMaps()
                .inNamespace(kafkaClusterNamespace(managedKafka))
                .withName(kafkaMetricsConfigMapName(managedKafka))
                .delete();
        kubernetesClient.configMaps()
                .inNamespace(kafkaClusterNamespace(managedKafka))
                .withName(zookeeperMetricsConfigMapName(managedKafka))
                .delete();
        if (isKafkaExternalCertificateEnabled) {
            kubernetesClient.secrets()
                    .inNamespace(kafkaClusterNamespace(managedKafka))
                    .withName(kafkaTlsSecretName(managedKafka))
                    .delete();
        }
        if (isKafkaAuthenticationEnabled) {
            kubernetesClient.secrets()
                    .inNamespace(kafkaClusterNamespace(managedKafka))
                    .withName(ssoClientSecretName(managedKafka))
                    .delete();
            kubernetesClient.secrets()
                    .inNamespace(kafkaClusterNamespace(managedKafka))
                    .withName(ssoTlsSecretName(managedKafka))
                    .delete();
        }
    }

    private void createOrUpdate(Kafka kafka) {
        // Kafka resource doesn't exist, has to be created
        if (kafkaResourceClient.getByName(kafka.getMetadata().getNamespace(), kafka.getMetadata().getName()) == null) {
            log.info("Creating Kafka instance {}/{}", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
            kafkaResourceClient.create(kafka);
            // Kafka resource already exists, has to be updated
        } else {
            log.info("Updating Kafka instance {}", kafka.getSpec().getKafka().getVersion());
            kafkaResourceClient.patch(kafka);
        }
    }

    private void createOrUpdate(Secret secret) {
        if (kubernetesClient.secrets()
                .inNamespace(secret.getMetadata().getNamespace())
                .withName(secret.getMetadata().getName()).get() == null) {
            kubernetesClient.secrets().inNamespace(secret.getMetadata().getNamespace()).createOrReplace(secret);
            // Secret resource already exists, has to be updated
        } else {
            kubernetesClient.secrets()
                    .inNamespace(secret.getMetadata().getNamespace())
                    .withName(secret.getMetadata().getName())
                    .patch(secret);
        }
    }

    private void createOrUpdate(ConfigMap configMap) {
        // ConfigMap resource doesn't exist, has to be created
        if (kubernetesClient.configMaps()
                .inNamespace(configMap.getMetadata().getNamespace())
                .withName(configMap.getMetadata().getName()).get() == null) {
            kubernetesClient.configMaps().inNamespace(configMap.getMetadata().getNamespace()).createOrReplace(configMap);
            // ConfigMap resource already exists, has to be updated
        } else {
            kubernetesClient.configMaps()
                    .inNamespace(configMap.getMetadata().getNamespace())
                    .withName(configMap.getMetadata().getName())
                    .patch(configMap);
        }
    }

    /* test */
    protected Kafka kafkaFrom(ManagedKafka managedKafka, Kafka current) {

        KafkaBuilder builder = current != null ? new KafkaBuilder(current) : new KafkaBuilder();

        Kafka kafka = builder
                .withNewApiVersion(Kafka.RESOURCE_GROUP + "/" + Kafka.V1BETA1)
                .editOrNewMetadata()
                    .withName(kafkaClusterName(managedKafka))
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withLabels(getKafkaLabels())
                .endMetadata()
                .editOrNewSpec()
                    .editOrNewKafka()
                        .withVersion(managedKafka.getSpec().getVersions().getKafka())
                        .withConfig(getKafkaConfig(managedKafka))
                        .withReplicas(KAFKA_BROKERS)
                        .withResources(getKafkaResources(managedKafka))
                        .withJvmOptions(getKafkaJvmOptions(managedKafka))
                        .withStorage(getKafkaStorage())
                        .withListeners(getKafkaListeners(managedKafka))
                        .withTemplate(getKafkaTemplate(managedKafka))
                        .withMetricsConfig(getKafkaMetricsConfig(managedKafka))
                    .endKafka()
                    .editOrNewZookeeper()
                        .withReplicas(ZOOKEEPER_NODES)
                        .withStorage((SingleVolumeStorage)getZooKeeperStorage())
                        .withResources(getZooKeeperResources(managedKafka))
                        .withJvmOptions(getZooKeeperJvmOptions(managedKafka))
                        .withTemplate(getZookeeperTemplate(managedKafka))
                        .withMetricsConfig(getZooKeeperMetricsConfig(managedKafka))
                    .endZookeeper()
                    .editOrNewKafkaExporter()
                        .withTopicRegex(".*")
                        .withGroupRegex(".*")
                    .endKafkaExporter()
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Kafka resource is needed
        // by the operator sdk to handle events on the Kafka resource properly
        OperandUtils.setAsOwner(managedKafka, kafka);

        return kafka;
    }

    /* test */
    protected ConfigMap configMapFrom(ManagedKafka managedKafka,  String name, ConfigMap current) {

        InputStream is = this.getClass().getClassLoader().getResourceAsStream(name + ".yaml");
        ConfigMap template = kubernetesClient.configMaps().load(is).get();

        ConfigMapBuilder builder = current != null ? new ConfigMapBuilder(current) : new ConfigMapBuilder(template);
        ConfigMap configMap = builder
                .editOrNewMetadata()
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withLabels(MANAGED_BY_LABELS)
                .endMetadata()
                .withData(template.getData())
                .build();

        // setting the ManagedKafka has owner of the ConfigMap resource is needed
        // by the operator sdk to handle events on the ConfigMap resource properly
        OperandUtils.setAsOwner(managedKafka, configMap);

        return configMap;
    }

    /* test */
    protected Secret tlsSecretFrom(ManagedKafka managedKafka, Secret current) {

        SecretBuilder builder = current != null ? new SecretBuilder(current) : new SecretBuilder();

        Map<String, String> certs = new HashMap<>(2);
        certs.put("tls.crt", encoder.encodeToString(managedKafka.getSpec().getEndpoint().getTls().getCert().getBytes()));
        certs.put("tls.key", encoder.encodeToString(managedKafka.getSpec().getEndpoint().getTls().getKey().getBytes()));
        Secret secret = builder
                .editOrNewMetadata()
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withName(kafkaTlsSecretName(managedKafka))
                    .withLabels(MANAGED_BY_LABELS)
                .endMetadata()
                .withType("kubernetes.io/tls")
                .withData(certs)
                .build();

        // setting the ManagedKafka has owner of the Secret resource is needed
        // by the operator sdk to handle events on the Secret resource properly
        OperandUtils.setAsOwner(managedKafka, secret);

        return secret;
    }

    /* test */
    protected Secret ssoClientSecretFrom(ManagedKafka managedKafka, Secret current) {

        SecretBuilder builder = current != null ? new SecretBuilder(current) : new SecretBuilder();

        Map<String, String> data = new HashMap<>(1);
        data.put("ssoClientSecret", encoder.encodeToString(managedKafka.getSpec().getOauth().getClientSecret().getBytes()));
        Secret secret = builder
                .editOrNewMetadata()
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withName(ssoClientSecretName(managedKafka))
                    .withLabels(MANAGED_BY_LABELS)
                .endMetadata()
                .withType("Opaque")
                .withData(data)
                .build();

        // setting the ManagedKafka has owner of the Secret resource is needed
        // by the operator sdk to handle events on the Secret resource properly
        OperandUtils.setAsOwner(managedKafka, secret);

        return secret;
    }

    /* test */
    protected Secret ssoTlsSecretFrom(ManagedKafka managedKafka, Secret current) {
        Map<String, String> certs = new HashMap<>(1);
        certs.put("keycloak.crt", encoder.encodeToString(managedKafka.getSpec().getOauth().getTlsTrustedCertificate().getBytes()));
        Secret secret = new SecretBuilder()
                .editOrNewMetadata()
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withName(ssoTlsSecretName(managedKafka))
                    .withLabels(MANAGED_BY_LABELS)
                .endMetadata()
                .withType("Opaque")
                .withData(certs)
                .build();

        // setting the ManagedKafka has owner of the Secret resource is needed
        // by the operator sdk to handle events on the Secret resource properly
        OperandUtils.setAsOwner(managedKafka, secret);

        return secret;
    }

    protected GenericSecretSource getSsoClientGenericSecretSource(ManagedKafka managedKafka) {
        return new GenericSecretSourceBuilder()
                .withSecretName(ssoClientSecretName(managedKafka))
                .withKey("ssoClientSecret")
                .build();
    }

    protected CertSecretSource getSsoTlsCertSecretSource(ManagedKafka managedKafka) {
        return new CertSecretSourceBuilder()
                .withSecretName(ssoTlsSecretName(managedKafka))
                .withCertificate("keycloak.crt")
                .build();
    }

    protected CertAndKeySecretSource getTlsCertAndKeySecretSource(ManagedKafka managedKafka) {
        if (!isKafkaExternalCertificateEnabled) {
            return null;
        }
        return new CertAndKeySecretSourceBuilder()
                .withSecretName(kafkaTlsSecretName(managedKafka))
                .withCertificate("tls.crt")
                .withKey("tls.key")
                .build();
    }

    private MetricsConfig getKafkaMetricsConfig(ManagedKafka managedKafka) {
        ConfigMapKeySelector cmSelector = new ConfigMapKeySelectorBuilder()
                .withName(kafkaMetricsConfigMapName(managedKafka))
                .withKey("my-key")
                .build();

        return new JmxPrometheusExporterMetricsBuilder()
                .withValueFrom(new ExternalConfigurationReferenceBuilder().withConfigMapKeyRef(cmSelector).build())
                .build();
    }

    private MetricsConfig getZooKeeperMetricsConfig(ManagedKafka managedKafka) {
        ConfigMapKeySelector cmSelector = new ConfigMapKeySelectorBuilder()
                .withName(zookeeperMetricsConfigMapName(managedKafka))
                .withKey("my-key")
                .build();

        return new JmxPrometheusExporterMetricsBuilder()
                .withValueFrom(new ExternalConfigurationReferenceBuilder().withConfigMapKeyRef(cmSelector).build())
                .build();
    }

    private KafkaClusterTemplate getKafkaTemplate(ManagedKafka managedKafka) {
        PodAntiAffinity podAntiAffinity = new PodAntiAffinityBuilder()
                .withRequiredDuringSchedulingIgnoredDuringExecution(
                        new PodAffinityTermBuilder().withTopologyKey("kubernetes.io/hostname").build()
                ).build();

        return new KafkaClusterTemplateBuilder()
                .withPod(new PodTemplateBuilder().withAffinity(new AffinityBuilder().withPodAntiAffinity(podAntiAffinity).build()).build())
                .build();
    }

    private ZookeeperClusterTemplate getZookeeperTemplate(ManagedKafka managedKafka) {
        PodAntiAffinity podAntiAffinity = new PodAntiAffinityBuilder()
                .withRequiredDuringSchedulingIgnoredDuringExecution(
                        new PodAffinityTermBuilder().withTopologyKey("kubernetes.io/hostname").build()
                ).build();

        return new ZookeeperClusterTemplateBuilder()
                .withPod(new PodTemplateBuilder().withAffinity(new AffinityBuilder().withPodAntiAffinity(podAntiAffinity).build()).build())
                .build();
    }

    private JvmOptions getKafkaJvmOptions(ManagedKafka managedKafka) {
        return new JvmOptionsBuilder().withXms("512m").withXmx("512m").build();
    }

    private JvmOptions getZooKeeperJvmOptions(ManagedKafka managedKafka) {
        return new JvmOptionsBuilder().withXms("512m").withXmx("512m").build();
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

    private Map<String, Object> getKafkaConfig(ManagedKafka managedKafka) {
        Map<String, Object> config = new HashMap<>();
        config.put("offsets.topic.replication.factor", 3);
        config.put("transaction.state.log.min.isr", 2);
        config.put("transaction.state.log.replication.factor", 3);
        config.put("auto.create.topics.enable", "false");
        config.put("min.insync.replicas", 2);
        config.put("default.replication.factor", 3);
        config.put("log.message.format.version", managedKafka.getSpec().getVersions().getKafka());
        config.put("inter.broker.protocol.version", managedKafka.getSpec().getVersions().getKafka());
        config.put("ssl.enabled.protocols", "TLSv1.3");
        config.put("ssl.protocol", "TLSv1.3");

        config.put("client.quota.callback.class", "org.apache.kafka.server.quota.StaticQuotaCallback");
        // Throttle at 4 MB/sec
        config.put("client.quota.callback.static.produce", String.valueOf(PRODUCE_QUOTA));
        config.put("client.quota.callback.static.consume", String.valueOf(CONSUME_QUOTA));

        // Start throttling when disk is above 90%. Full stop at 95%.
        config.put("client.quota.callback.static.storage.soft", String.valueOf((long)(0.9 * Quantity.getAmountInBytes(KAFKA_VOLUME_SIZE).doubleValue())));
        config.put("client.quota.callback.static.storage.hard", String.valueOf((long)(0.95 * Quantity.getAmountInBytes(KAFKA_VOLUME_SIZE).doubleValue())));

        // Check storage every 30 seconds
        config.put("client.quota.callback.static.storage.check-interval", "30");
        config.put("quota.window.num", "30");
        config.put("quota.window.size.seconds", "2");

        // Limit client connections to 500 per broker
        config.put("max.connections", "500");

        return config;
    }

    private ArrayOrObjectKafkaListeners getKafkaListeners(ManagedKafka managedKafka) {

        KafkaListenerAuthentication plainOverOauthAuthenticationListener = null;
        KafkaListenerAuthentication oauthAuthenticationListener = null;

        if (isKafkaAuthenticationEnabled) {
            ManagedKafkaAuthenticationOAuth managedKafkaAuthenticationOAuth = managedKafka.getSpec().getOauth();

            plainOverOauthAuthenticationListener = new KafkaListenerAuthenticationOAuthBuilder()
                    .withClientId(managedKafkaAuthenticationOAuth.getClientId())
                    .withJwksEndpointUri(managedKafkaAuthenticationOAuth.getJwksEndpointURI())
                    .withUserNameClaim(managedKafkaAuthenticationOAuth.getUserNameClaim())
                    .withCustomClaimCheck(managedKafkaAuthenticationOAuth.getCustomClaimCheck())
                    .withValidIssuerUri(managedKafkaAuthenticationOAuth.getValidIssuerEndpointURI())
                    .withTlsTrustedCertificates(getSsoTlsCertSecretSource(managedKafka))
                    .withClientSecret(getSsoClientGenericSecretSource(managedKafka))
                    .withEnablePlain(true)
                    .withTokenEndpointUri(managedKafkaAuthenticationOAuth.getTokenEndpointURI())
                    .build();

            oauthAuthenticationListener = new KafkaListenerAuthenticationOAuthBuilder()
                    .withClientId(managedKafkaAuthenticationOAuth.getClientId())
                    .withJwksEndpointUri(managedKafkaAuthenticationOAuth.getJwksEndpointURI())
                    .withUserNameClaim(managedKafkaAuthenticationOAuth.getUserNameClaim())
                    .withCustomClaimCheck(managedKafkaAuthenticationOAuth.getCustomClaimCheck())
                    .withValidIssuerUri(managedKafkaAuthenticationOAuth.getValidIssuerEndpointURI())
                    .withTlsTrustedCertificates(getSsoTlsCertSecretSource(managedKafka))
                    .withClientSecret(getSsoClientGenericSecretSource(managedKafka))
                    .build();
        }

        KafkaListenerType externalListenerType = kubernetesClient.isAdaptable(OpenShiftClient.class) ? KafkaListenerType.ROUTE : KafkaListenerType.INGRESS;

        return new ArrayOrObjectKafkaListenersBuilder()
                .withGenericKafkaListeners(
                        new GenericKafkaListenerBuilder()
                                .withName("plain")
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .build(),
                        new GenericKafkaListenerBuilder()
                                .withName("tls")
                                .withPort(9093)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(true)
                                .build(),
                        new GenericKafkaListenerBuilder()
                                .withName("external")
                                .withPort(9094)
                                .withType(externalListenerType)
                                .withTls(true)
                                .withAuth(plainOverOauthAuthenticationListener)
                                .withConfiguration(
                                        new GenericKafkaListenerConfigurationBuilder()
                                                .withBootstrap(new GenericKafkaListenerConfigurationBootstrapBuilder()
                                                        .withHost(managedKafka.getSpec().getEndpoint().getBootstrapServerHost())
                                                        .build()
                                                )
                                                .withBrokers(getBrokerOverrides(managedKafka))
                                                .withBrokerCertChainAndKey(getTlsCertAndKeySecretSource(managedKafka))
                                        .build()
                                )
                                .build(),
                        new GenericKafkaListenerBuilder()
                                .withName("oauth")
                                .withPort(9095)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .withAuth(oauthAuthenticationListener)
                                .build()
                ).build();
    }

    private List<GenericKafkaListenerConfigurationBroker> getBrokerOverrides(ManagedKafka managedKafka) {
        List<GenericKafkaListenerConfigurationBroker> brokerOverrides = new ArrayList<>(KAFKA_BROKERS);
        for (int i = 0; i < KAFKA_BROKERS; i++) {
            brokerOverrides.add(
                    new GenericKafkaListenerConfigurationBrokerBuilder()
                            .withHost(String.format("broker-%d-%s", i, managedKafka.getSpec().getEndpoint().getBootstrapServerHost()))
                            .withBroker(i)
                    .build()
            );
        }
        return brokerOverrides;
    }

    private Storage getKafkaStorage() {
        return new JbodStorageBuilder()
                .withVolumes(
                        new PersistentClaimStorageBuilder()
                                .withId(JBOD_VOLUME_ID)
                                .withSize(KAFKA_VOLUME_SIZE.toString())
                                .withDeleteClaim(DELETE_CLAIM)
                                .withStorageClass(KAFKA_STORAGE_CLASS)
                                .build()
                )
                .build();
    }

    private Storage getZooKeeperStorage() {
        return new PersistentClaimStorageBuilder()
                .withSize(ZOOKEEPER_VOLUME_SIZE.toString())
                .withDeleteClaim(DELETE_CLAIM)
                .withStorageClass(KAFKA_STORAGE_CLASS)
                .build();
    }

    private Map<String, String> getKafkaLabels() {
        Map<String, String> labels = new HashMap<>(2);
        labels.put("ingressType", "sharded");
        labels.put("app.kubernetes.io/managed-by", "kas-fleetshard-operator");
        return labels;
    }

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        Kafka kafka = cachedDeployment(managedKafka);
        boolean isInstalling = kafka == null || kafka.getStatus() == null ||
                (kafkaCondition(kafka).getType().equals("NotReady")
                && kafkaCondition(kafka).getStatus().equals("True")
                && kafkaCondition(kafka).getReason().equals("Creating"));
        log.info("KafkaCluster isInstalling = {}", isInstalling);
        return isInstalling;
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        Kafka kafka = cachedDeployment(managedKafka);
        boolean isReady = kafka != null && (kafka.getStatus() == null ||
                (kafkaCondition(kafka).getType().equals("Ready") && kafkaCondition(kafka).getStatus().equals("True")));
        log.info("KafkaCluster isReady = {}", isReady);
        return isReady;
    }

    @Override
    public boolean isError(ManagedKafka managedKafka) {
        Kafka kafka = cachedDeployment(managedKafka);
        boolean isError = kafka != null && kafka.getStatus() != null
                && kafkaCondition(kafka).getType().equals("NotReady")
                && kafkaCondition(kafka).getStatus().equals("True")
                && !kafkaCondition(kafka).getReason().equals("Creating");
        log.info("KafkaCluster isError = {}", isError);
        return isError;
    }

    private Condition kafkaCondition(Kafka kafka) {
        return kafka.getStatus().getConditions().get(0);
    }

    private Kafka cachedDeployment(ManagedKafka managedKafka) {
        return informerManager.getLocalKafka(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
    }

    private ConfigMap cachedConfigMap(ManagedKafka managedKafka, String name) {
        return informerManager.getLocalConfigMap(kafkaClusterNamespace(managedKafka), name);
    }

    private Secret cachedSecret(ManagedKafka managedKafka, String name) {
        return informerManager.getLocalSecret(kafkaClusterNamespace(managedKafka), name);
    }

    public static String kafkaClusterName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName();
    }

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    public static String kafkaTlsSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-tls-secret";
    }

    public static String ssoClientSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-sso-secret";
    }

    public static String ssoTlsSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-sso-cert";
    }

    public static String kafkaMetricsConfigMapName(ManagedKafka managedKafka) {
        return "kafka-metrics";
    }

    public static String zookeeperMetricsConfigMapName(ManagedKafka managedKafka) {
        return "zookeeper-metrics";
    }
}
