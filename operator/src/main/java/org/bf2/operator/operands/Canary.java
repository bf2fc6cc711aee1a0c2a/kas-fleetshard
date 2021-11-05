package org.bf2.operator.operands;

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
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.Startup;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.managers.SecuritySecretManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@Startup
@ApplicationScoped
@DefaultBean
public class Canary extends AbstractCanary {

    private static final Quantity CONTAINER_MEMORY_REQUEST = new Quantity("32Mi");
    private static final Quantity CONTAINER_CPU_REQUEST = new Quantity("5m");
    private static final Quantity CONTAINER_MEMORY_LIMIT = new Quantity("64Mi");
    private static final Quantity CONTAINER_CPU_LIMIT = new Quantity("10m");

    @ConfigProperty(name = "image.canary")
    String canaryImage;

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    @Inject
    protected KafkaInstanceConfiguration config;

    @Override
    public Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current) {
        String canaryName = canaryName(managedKafka);

        DeploymentBuilder builder = current != null ? new DeploymentBuilder(current) : new DeploymentBuilder();

        builder
                .editOrNewMetadata()
                    .withName(canaryName)
                    .withNamespace(canaryNamespace(managedKafka))
                    .withLabels(buildLabels(canaryName))
                .endMetadata()
                .editOrNewSpec()
                    .withReplicas(1)
                    .editOrNewSelector()
                        .withMatchLabels(buildSelectorLabels(canaryName))
                    .endSelector()
                    .editOrNewTemplate()
                        .editOrNewMetadata()
                            .withLabels(buildLabels(canaryName))
                        .endMetadata()
                        .editOrNewSpec()
                            .withContainers(buildContainers(managedKafka, current))
                            .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                            .withVolumes(buildVolumes(managedKafka))
                        .endSpec()
                    .endTemplate()
                .endSpec();

        if(this.config.getCanary().isColocateWithZookeeper()) {
            builder
                .editOrNewSpec()
                    .editOrNewTemplate()
                        .editOrNewSpec()
                        .withAffinity(OperandUtils.buildZookeeperPodAffinity(managedKafka))
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

    private List<Volume> buildVolumes(ManagedKafka managedKafka) {
        return Collections.singletonList(
                new VolumeBuilder()
                        .withName(canaryTlsVolumeName(managedKafka))
                        .editOrNewSecret()
                            .withSecretName(SecuritySecretManager.strimziClusterCaCertSecret(managedKafka))
                        .endSecret()
                        .build()
        );
    }

    protected List<Container> buildContainers(ManagedKafka managedKafka, Deployment current) {
        Container container = new ContainerBuilder()
                .withName("canary")
                .withImage(canaryImage)
                .withEnv(buildEnvVar(managedKafka, current))
                .withPorts(buildContainerPorts())
                .withResources(buildResources())
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

    private Map<String, String> buildLabels(String canaryName) {
        Map<String, String> labels = buildSelectorLabels(canaryName);
        labels.put("app.kubernetes.io/component", "canary");
        return labels;
    }

    private List<EnvVar> buildEnvVar(ManagedKafka managedKafka, Deployment current) {
        List<EnvVar> envVars = new ArrayList<>(10);
        envVars.add(new EnvVarBuilder().withName("KAFKA_BOOTSTRAP_SERVERS").withValue(managedKafka.getMetadata().getName() + "-kafka-bootstrap:9093").build());
        envVars.add(new EnvVarBuilder().withName("RECONCILE_INTERVAL_MS").withValue("5000").build());
        envVars.add(new EnvVarBuilder().withName("EXPECTED_CLUSTER_SIZE").withValue(String.valueOf(this.config.getKafka().getReplicas())).build());
        String kafkaVersion = managedKafka.getSpec().getVersions().getKafka();
        // takes the current Kafka version if the canary already exists. During Kafka upgrades it doesn't have to change, as any other clients.
        if (current != null) {
            Optional<EnvVar> kafkaVersionEnvVar = current.getSpec().getTemplate().getSpec().getContainers().stream()
                    .filter(container -> "canary".equals(container.getName()))
                    .findFirst()
                    .get().getEnv().stream()
                    .filter(ev -> "KAFKA_VERSION".equals(ev.getName()))
                    .findFirst();
            if (kafkaVersionEnvVar.isPresent()) {
                kafkaVersion = kafkaVersionEnvVar.get().getValue();
            }
        }
        envVars.add(new EnvVarBuilder().withName("KAFKA_VERSION").withValue(kafkaVersion).build());
        envVars.add(new EnvVarBuilder().withName("TZ").withValue("UTC").build());
        envVars.add(new EnvVarBuilder().withName("TLS_ENABLED").withValue("true").build());
        envVars.add(new EnvVarBuilder().withName("TLS_CA_CERT").withValue("/tmp/tls-ca-cert/ca.crt").build());

        EnvVarSource saramaLogEnabled =
                new EnvVarSourceBuilder()
                        .editOrNewConfigMapKeyRef()
                            .withName("canary-config")
                            .withKey("sarama.log.enabled")
                            .withOptional(Boolean.TRUE)
                        .endConfigMapKeyRef()
                        .build();

        EnvVarSource verbosityLogLevel =
                new EnvVarSourceBuilder()
                        .editOrNewConfigMapKeyRef()
                            .withName("canary-config")
                            .withKey("verbosity.log.level")
                            .withOptional(Boolean.TRUE)
                        .endConfigMapKeyRef()
                        .build();

        envVars.add(new EnvVarBuilder().withName("SARAMA_LOG_ENABLED").withValueFrom(saramaLogEnabled).build());
        envVars.add(new EnvVarBuilder().withName("VERBOSITY_LOG_LEVEL").withValueFrom(verbosityLogLevel).build());
        envVars.add(new EnvVarBuilder().withName("TOPIC_CONFIG").withValue("retention.ms=600000;segment.bytes=16384").build());

        Optional<ServiceAccount> canaryServiceAccount = managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary);
        if (canaryServiceAccount.isPresent()) {
            envVars.add(new EnvVarBuilder().withName("SASL_MECHANISM").withValue("PLAIN").build());
            envVars.add(new EnvVarBuilder().withName("SASL_USER").withValue(canaryServiceAccount.get().getPrincipal()).build());
            envVars.add(new EnvVarBuilder().withName("SASL_PASSWORD").withValue(canaryServiceAccount.get().getPassword()).build());
        }
        return envVars;
    }

    private List<ContainerPort> buildContainerPorts() {
        return Collections.singletonList(new ContainerPortBuilder().withName("metrics").withContainerPort(8080).build());
    }

    private ResourceRequirements buildResources() {
        ResourceRequirements resources = new ResourceRequirementsBuilder()
                .addToRequests("memory", CONTAINER_MEMORY_REQUEST)
                .addToRequests("cpu", CONTAINER_CPU_REQUEST)
                .addToLimits("memory", CONTAINER_MEMORY_LIMIT)
                .addToLimits("cpu", CONTAINER_CPU_LIMIT)
                .build();
        return resources;
    }

    private List<VolumeMount> buildVolumeMounts(ManagedKafka managedKafka) {
        return Collections.singletonList(
                new VolumeMountBuilder()
                .withName(canaryTlsVolumeName(managedKafka))
                .withMountPath("/tmp/tls-ca-cert")
                .build()
        );
    }

    public static String canaryTlsVolumeName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-tls-ca-cert";
    }
}
