package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.quarkus.arc.DefaultBean;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.managers.IngressControllerManager;
import org.bf2.operator.managers.OperandOverrideManager;
import org.bf2.operator.managers.SecuritySecretManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@DefaultBean
public class Canary extends AbstractCanary {

    private static final int METRICS_PORT = 8080;
    private static final String METRICS_PORT_NAME = "metrics";
    private static final IntOrString METRICS_PORT_TARGET = new IntOrString(METRICS_PORT_NAME);
    private static final String CANARY_CONFIG_CONFIGMAP_NAME = "canary-config";
    private static final String CANARY_CONFIG_VOLUME_NAME = "config-volume";
    private static final Path CANARY_DYNAMIC_CONFIG_JSON = Path.of("/opt/etc", "canary-config.json");

    @ConfigProperty(name = "managedkafka.canary.producer-latency-buckets")
    String producerLatencyBuckets;

    @ConfigProperty(name = "managedkafka.canary.endtoend-latency-buckets")
    String endToEndLatencyBuckets;

    @ConfigProperty(name = "managedkafka.canary.connection-check-latency-buckets")
    String connectionCheckLatencyBuckets;

    @ConfigProperty(name = "managedkafka.canary.status-time-window-ms")
    Long statusTimeWindowMs;

    @ConfigProperty(name = "managedkafka.canary.init-timeout-seconds")
    String initTimeoutSeconds;

    @ConfigProperty(name = "managedkafka.canary.init-enabled")
    Boolean initEnabled;

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    @Inject
    protected Instance<IngressControllerManager> ingressControllerManagerInstance;

    @Inject
    protected KafkaInstanceConfigurations configs;

    @Inject
    protected OperandOverrideManager overrideManager;

    @Inject
    protected AbstractKafkaCluster kafkaCluster;

    @Override
    public Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current) {
        String canaryName = canaryName(managedKafka);

        DeploymentBuilder builder = current != null ? new DeploymentBuilder(current) : new DeploymentBuilder();

        Affinity affinity = OperandUtils.buildAffinity(informerManager.getLocalAgent(), managedKafka,
                this.configs.getConfig(managedKafka).getCanary().isColocateWithZookeeper());

        builder
                .editOrNewMetadata()
                    .withName(canaryName)
                    .withNamespace(canaryNamespace(managedKafka))
                    .withLabels(buildLabels(canaryName, managedKafka))
                .endMetadata()
                .editOrNewSpec()
                    .withReplicas(1)
                    .editOrNewSelector()
                        .withMatchLabels(buildSelectorLabels(canaryName))
                    .endSelector()
                    .editOrNewTemplate()
                        .editOrNewMetadata()
                            .withLabels(buildLabels(canaryName, managedKafka))
                        .endMetadata()
                        .editOrNewSpec()
                            .withInitContainers()
                            .withContainers(buildContainers(managedKafka, current))
                            .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                            .withVolumes(buildVolumes(managedKafka))
                            .withAffinity(affinity)
                            .withTolerations(OperandUtils.profileTolerations(managedKafka))
                        .endSpec()
                    .endTemplate()
                .endSpec();

        if (initEnabled && !hasClusterSpecificBootstrapDomain(managedKafka)) {
            builder
                .editOrNewSpec()
                    .editOrNewTemplate()
                        .editOrNewSpec()
                            .withInitContainers(buildInitContainer(managedKafka, current))
                        .endSpec()
                    .endTemplate()
                .endSpec();
        }

        Deployment deployment = builder.build();

        // setting the ManagedKafka has owner of the Canary deployment resource is needed
        // by the operator sdk to handle events on the Deployment resource properly
        OperandUtils.setAsOwner(managedKafka, deployment);

        return deployment;
    }

    @Override
    public Service serviceFrom(ManagedKafka managedKafka, Service current) {
        String canaryName = canaryName(managedKafka);

        ServiceBuilder builder = current != null ? new ServiceBuilder(current) : new ServiceBuilder();

        Service service = builder
                .editOrNewMetadata()
                    .withNamespace(canaryNamespace(managedKafka))
                    .withName(canaryName)
                    .withLabels(buildLabels(canaryName, managedKafka))
                .endMetadata()
                .editOrNewSpec()
                    .withClusterIP(null) // to prevent 422 errors
                    .withSelector(buildSelectorLabels(canaryName))
                    .withPorts(buildServicePorts(managedKafka))
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Canary service resource is needed
        // by the operator sdk to handle events on the Service resource properly
        OperandUtils.setAsOwner(managedKafka, service);

        return service;
    }

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        if (managedKafka.isReserveDeployment()) {
            Deployment current = cachedDeployment(managedKafka);
            Deployment deployment = deploymentFrom(managedKafka, null);

            deployment = ReservedDeploymentConverter.asReservedDeployment(current, deployment, managedKafka);

            if (!Objects.equals(current, deployment)) {
                createOrUpdate(deployment);
            }
            return;
        }
        super.createOrUpdate(managedKafka);
    }

    private List<Volume> buildVolumes(ManagedKafka managedKafka) {
        final String tlsSecretName;

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            tlsSecretName = SecuritySecretManager.kafkaTlsSecretName(managedKafka);
        } else {
            tlsSecretName = SecuritySecretManager.strimziClusterCaCertSecret(managedKafka);
        }

        return List.of(
                new VolumeBuilder()
                        .withName(canaryTlsVolumeName(managedKafka))
                        .editOrNewSecret()
                        .withSecretName(tlsSecretName)
                        .endSecret()
                        .build(),
                new VolumeBuilder()
                        .withName(CANARY_CONFIG_VOLUME_NAME)
                        .editOrNewConfigMap()
                        .withName(CANARY_CONFIG_CONFIGMAP_NAME)
                        .withOptional(true)
                        .endConfigMap()
                        .build()
        );
    }

    protected Container buildInitContainer(ManagedKafka managedKafka, Deployment current) {
        return new ContainerBuilder()
                .withName("init")
                .withImage(overrideManager.getCanaryInitImage(managedKafka.getSpec().getVersions().getStrimzi()))
                .withEnv(buildInitEnvVar(managedKafka))
                .withResources(this.configs.getConfig(managedKafka).getCanary().buildResources())
                .withCommand("/opt/strimzi-canary-tool/canary-dns-init.sh")
                .build();
    }

    protected boolean hasClusterSpecificBootstrapDomain(ManagedKafka managedKafka) {
        return ingressControllerManagerInstance.isResolvable() &&
                managedKafka.getSpec().getEndpoint().getBootstrapServerHost()
                    .endsWith(ingressControllerManagerInstance.get().getClusterDomain());
    }

    protected List<Container> buildContainers(ManagedKafka managedKafka, Deployment current) {
        Container container = new ContainerBuilder()
                .withName("canary")
                .withImage(overrideManager.getCanaryImage(managedKafka.getSpec().getVersions().getStrimzi()))
                .withEnv(buildEnvVar(managedKafka, current))
                .withPorts(buildContainerPorts())
                .withResources(this.configs.getConfig(managedKafka).getCanary().buildResources())
                .withReadinessProbe(buildReadinessProbe())
                .withLivenessProbe(buildLivenessProbe())
                .withVolumeMounts(buildVolumeMounts(managedKafka))
                .build();

        return Collections.singletonList(container);
    }

    private Probe buildLivenessProbe() {
        return new ProbeBuilder()
                .withHttpGet(
                        new HTTPGetActionBuilder()
                                .withPath("/liveness")
                                .withPort(new IntOrString(8080))
                                .build()
                )
                .withTimeoutSeconds(5)
                .withInitialDelaySeconds(15)
                .build();
    }

    private Probe buildReadinessProbe() {
        return new ProbeBuilder()
                .withHttpGet(
                        new HTTPGetActionBuilder()
                                .withPath("/readiness")
                                .withPort(new IntOrString(8080))
                                .build()
                )
                .withTimeoutSeconds(5)
                .withInitialDelaySeconds(15)
                .build();
    }

    private Map<String, String> buildSelectorLabels(String canaryName) {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        labels.put("app", canaryName);
        return labels;
    }

    Map<String, String> buildLabels(String canaryName, ManagedKafka managedKafka) {
        Map<String, String> labels = buildSelectorLabels(canaryName);
        Optional.ofNullable(managedKafka.getMetadata().getLabels()).ifPresent(labels::putAll);
        labels.put("app.kubernetes.io/component", "canary");
        return labels;
    }

    private static String getBootstrapURL(ManagedKafka managedKafka) {
        return managedKafka.getSpec().getEndpoint().getBootstrapServerHost() + ":443";
    }

    private List<EnvVar> buildInitEnvVar(ManagedKafka managedKafka) {
        List<EnvVar> envVars = List.of(
                new EnvVarBuilder().withName("KAFKA_BOOTSTRAP_SERVERS").withValue(getBootstrapURL(managedKafka)).build(),
                new EnvVarBuilder().withName("INIT_TIMEOUT_SECONDS").withValue(String.valueOf(initTimeoutSeconds)).build());

        return this.overrideManager.getCanaryOverride(managedKafka.getSpec().getVersions().getStrimzi()).init.applyEnvironmentTo(envVars);
    }

    private List<EnvVar> buildEnvVar(ManagedKafka managedKafka, Deployment current) {
        List<EnvVar> envVars = new ArrayList<>(10);
        String bootstrap = getBootstrapURL(managedKafka);
        envVars.add(new EnvVarBuilder().withName("KAFKA_BOOTSTRAP_SERVERS").withValue(bootstrap).build());
        envVars.add(new EnvVarBuilder().withName("RECONCILE_INTERVAL_MS").withValue("5000").build());
        envVars.add(new EnvVarBuilder().withName("EXPECTED_CLUSTER_SIZE").withValue(String.valueOf(kafkaCluster.getReplicas(managedKafka))).build());
        String kafkaVersion = managedKafka.getSpec().getVersions().getKafka();
        // takes the current Kafka version if the canary already exists. During Kafka upgrades it doesn't have to change, as any other clients.
        if (current != null) {
            Optional<EnvVar> kafkaVersionEnvVar = current.getSpec().getTemplate().getSpec().getContainers().stream()
                    .filter(container -> "canary".equals(container.getName()))
                    .map(Container::getEnv)
                    .flatMap(Collection::stream)
                    .filter(ev -> "KAFKA_VERSION".equals(ev.getName()))
                    .findFirst();

            if (kafkaVersionEnvVar.isPresent()) {
                kafkaVersion = kafkaVersionEnvVar.get().getValue();
            }

        }
        envVars.add(new EnvVarBuilder().withName("KAFKA_VERSION").withValue(kafkaVersion).build());
        envVars.add(new EnvVarBuilder().withName("TZ").withValue("UTC").build());
        envVars.add(new EnvVarBuilder().withName("TLS_ENABLED").withValue("true").build());

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            envVars.add(new EnvVarBuilder().withName("TLS_CA_CERT").withValue("/tmp/tls-ca-cert/tls.crt").build());
        } else {
            envVars.add(new EnvVarBuilder().withName("TLS_CA_CERT").withValue("/tmp/tls-ca-cert/ca.crt").build());
        }

        // Deprecated
        EnvVarSource saramaLogEnabled =
                new EnvVarSourceBuilder()
                        .editOrNewConfigMapKeyRef()
                            .withName(CANARY_CONFIG_CONFIGMAP_NAME)
                            .withKey("sarama.log.enabled")
                            .withOptional(Boolean.TRUE)
                        .endConfigMapKeyRef()
                        .build();

        EnvVarSource verbosityLogLevel =
                new EnvVarSourceBuilder()
                        .editOrNewConfigMapKeyRef()
                            .withName(CANARY_CONFIG_CONFIGMAP_NAME)
                            .withKey("verbosity.log.level")
                            .withOptional(Boolean.TRUE)
                        .endConfigMapKeyRef()
                        .build();

        EnvVarSource goDebug =
                new EnvVarSourceBuilder()
                        .editOrNewConfigMapKeyRef()
                            .withName(CANARY_CONFIG_CONFIGMAP_NAME)
                            .withKey("go.debug")
                            .withOptional(Boolean.TRUE)
                        .endConfigMapKeyRef()
                        .build();

        envVars.add(new EnvVarBuilder().withName("SARAMA_LOG_ENABLED").withValueFrom(saramaLogEnabled).build());
        envVars.add(new EnvVarBuilder().withName("VERBOSITY_LOG_LEVEL").withValueFrom(verbosityLogLevel).build());
        envVars.add(new EnvVarBuilder().withName("GODEBUG").withValueFrom(goDebug).build());
        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        envVars.add(new EnvVarBuilder().withName("TOPIC").withValue(config.getCanary().getTopic()).build());
        envVars.add(new EnvVarBuilder().withName("TOPIC_CONFIG").withValue("retention.ms=600000;segment.bytes=16384").build());
        envVars.add(new EnvVarBuilder().withName("CLIENT_ID").withValue(config.getCanary().getClientId()).build());
        envVars.add(new EnvVarBuilder().withName("CONSUMER_GROUP_ID").withValue(config.getCanary().getConsumerGroupId()).build());
        envVars.add(new EnvVarBuilder().withName("PRODUCER_LATENCY_BUCKETS").withValue(producerLatencyBuckets).build());
        envVars.add(new EnvVarBuilder().withName("ENDTOEND_LATENCY_BUCKETS").withValue(endToEndLatencyBuckets).build());
        envVars.add(new EnvVarBuilder().withName("CONNECTION_CHECK_LATENCY_BUCKETS").withValue(connectionCheckLatencyBuckets).build());
        envVars.add(new EnvVarBuilder().withName("DYNAMIC_CONFIG_FILE").withValue(CANARY_DYNAMIC_CONFIG_JSON.toString()).build());

        if (SecuritySecretManager.isCanaryServiceAccountPresent(managedKafka)){
            envVars.add(new EnvVarBuilder().withName("SASL_MECHANISM").withValue("PLAIN").build());
            addEnvVarFromSecret(envVars, "SASL_USER",SecuritySecretManager.canarySaslSecretName(managedKafka) , SecuritySecretManager.SASL_PRINCIPAL);
            addEnvVarFromSecret(envVars, "SASL_PASSWORD",SecuritySecretManager.canarySaslSecretName(managedKafka) , SecuritySecretManager.SASL_PASSWORD);
        }
        envVars.add(new EnvVarBuilder().withName("STATUS_TIME_WINDOW_MS").withValue(String.valueOf(statusTimeWindowMs)).build());
        return this.overrideManager.getCanaryOverride(managedKafka.getSpec().getVersions().getStrimzi()).applyEnvironmentTo(envVars);
    }

    private List<ContainerPort> buildContainerPorts() {
        return Collections.singletonList(new ContainerPortBuilder().withName(METRICS_PORT_NAME).withContainerPort(METRICS_PORT).build());
    }

    private List<VolumeMount> buildVolumeMounts(ManagedKafka managedKafka) {
        Path parent = CANARY_DYNAMIC_CONFIG_JSON.getParent();
        if (parent == null) {
            throw new IllegalStateException(String.format("%s has unacceptable parent path", CANARY_DYNAMIC_CONFIG_JSON));
        }
        return List.of(
                new VolumeMountBuilder()
                .withName(canaryTlsVolumeName(managedKafka))
                .withMountPath("/tmp/tls-ca-cert")
                .build(),
                new VolumeMountBuilder()
                        .withName(CANARY_CONFIG_VOLUME_NAME)
                        .withMountPath(parent.toString())
                .build()
        );
    }

    private List<ServicePort> buildServicePorts(ManagedKafka managedKafka) {
        return Collections.singletonList(new ServicePortBuilder()
                .withName(METRICS_PORT_NAME)
                .withProtocol("TCP")
                .withPort(METRICS_PORT)
                .withTargetPort(METRICS_PORT_TARGET)
                .build());
    }

    private void addEnvVarFromSecret(List<EnvVar> envVars, String envName, String secretName, String secretEntry) {
        envVars.add(new EnvVarBuilder()
                .withName(envName)
                .withNewValueFrom()
                .withNewSecretKeyRef(secretEntry, secretName, false)
                .endValueFrom()
                .build());
    }

    public static String canaryTlsVolumeName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-tls-ca-cert";
    }
}
