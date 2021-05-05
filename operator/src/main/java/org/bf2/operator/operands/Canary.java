package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.quarkus.arc.DefaultBean;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@DefaultBean
public class Canary extends AbstractCanary {

    private static final Quantity CONTAINER_MEMORY_REQUEST = new Quantity("32Mi");
    private static final Quantity CONTAINER_CPU_REQUEST = new Quantity("5m");
    private static final Quantity CONTAINER_MEMORY_LIMIT = new Quantity("64Mi");
    private static final Quantity CONTAINER_CPU_LIMIT = new Quantity("10m");

    @ConfigProperty(name = "image.canary")
    String canaryImage;

    @Override
    protected Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current) {
        String canaryName = canaryName(managedKafka);

        DeploymentBuilder builder = current != null ? new DeploymentBuilder(current) : new DeploymentBuilder();

        Deployment deployment = builder
                .editOrNewMetadata()
                    .withName(canaryName)
                    .withNamespace(canaryNamespace(managedKafka))
                    .withLabels(getLabels(canaryName))
                .endMetadata()
                .editOrNewSpec()
                    .withReplicas(1)
                    .editOrNewSelector()
                        .withMatchLabels(getSelectorLabels(canaryName))
                    .endSelector()
                    .editOrNewTemplate()
                        .editOrNewMetadata()
                            .withLabels(getLabels(canaryName))
                        .endMetadata()
                        .editOrNewSpec()
                            .withContainers(getContainers(managedKafka))
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Canary deployment resource is needed
        // by the operator sdk to handle events on the Deployment resource properly
        OperandUtils.setAsOwner(managedKafka, deployment);

        return deployment;
    }

    private List<Container> getContainers(ManagedKafka managedKafka) {
        Container container = new ContainerBuilder()
                .withName("canary")
                .withImage(canaryImage)
                .withEnv(getEnvVar(managedKafka))
                .withPorts(getContainerPorts())
                .withResources(getResources())
                .withReadinessProbe(getReadinessProbe())
                .withLivenessProbe(getLivenessProbe())
                .build();

        return Collections.singletonList(container);
    }

    private Probe getLivenessProbe() {
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

    private Probe getReadinessProbe() {
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

    private Map<String, String> getSelectorLabels(String canaryName) {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        labels.put("app", canaryName);
        return labels;
    }

    private Map<String, String> getLabels(String canaryName) {
        Map<String, String> labels = getSelectorLabels(canaryName);
        labels.put("app.kubernetes.io/component", "canary");
        return labels;
    }

    private List<EnvVar> getEnvVar(ManagedKafka managedKafka) {
        List<EnvVar> envVars = new ArrayList<>(3);
        envVars.add(new EnvVarBuilder().withName("KAFKA_BOOTSTRAP_SERVERS").withValue(managedKafka.getMetadata().getName() + "-kafka-bootstrap:9092").build());
        envVars.add(new EnvVarBuilder().withName("RECONCILE_INTERVAL_MS").withValue("5000").build());
        envVars.add(new EnvVarBuilder().withName("EXPECTED_CLUSTER_SIZE").withValue(String.valueOf(KafkaCluster.KAFKA_BROKERS)).build());
        return envVars;
    }

    private List<ContainerPort> getContainerPorts() {
        return Collections.singletonList(new ContainerPortBuilder().withName("metrics").withContainerPort(8080).build());
    }

    private ResourceRequirements getResources() {
        ResourceRequirements resources = new ResourceRequirementsBuilder()
                .addToRequests("memory", CONTAINER_MEMORY_REQUEST)
                .addToRequests("cpu", CONTAINER_CPU_REQUEST)
                .addToLimits("memory", CONTAINER_MEMORY_LIMIT)
                .addToLimits("cpu", CONTAINER_CPU_LIMIT)
                .build();
        return resources;
    }
}
