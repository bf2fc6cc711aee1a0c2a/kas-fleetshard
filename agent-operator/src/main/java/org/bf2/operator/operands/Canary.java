package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
public class Canary {

    @Inject
    private KubernetesClient kubernetesClient;

    private Deployment deployment;

    public void createOrUpdate(ManagedKafka managedKafka) {
        String canaryName = managedKafka.getMetadata().getName() + "-canary";

        // Canary deployment resource doesn't exist, has to be created
        if (kubernetesClient.apps().deployments().withName(canaryName).get() == null) {
            deployment = deploymentFrom(managedKafka);
            kubernetesClient.apps().deployments().create(deployment);
        // Canary deployment resource already exists, has to be updated
        } else {
            // TODO: updating the Canary deployment
        }
    }

    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kubernetesClient.apps()
                .deployments()
                .inNamespace(managedKafka.getMetadata().getNamespace())
                .withName(managedKafka.getMetadata().getName() + "-canary")
                .delete();
    }

    private Deployment deploymentFrom(ManagedKafka managedKafka) {

        String canaryName = managedKafka.getMetadata().getName() + "-canary";

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(canaryName)
                .endMetadata()
                .withNewSpec()
                    .withNewSelector()
                        .addToMatchLabels(getLabels(canaryName))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels(getLabels(canaryName))
                        .endMetadata()
                        .withNewSpec()
                            .addNewContainer()
                                .withName(canaryName)
                                .withImage("quay.io/ppatierno/strimzi-canary:0.0.2")
                                .withEnv(getEnvVar(managedKafka))
                                .withPorts(getContainerPorts())
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        return deployment;
    }

    private static Map<String, String> getLabels(String canaryName) {
        // TODO: adding label about observability
        return Collections.singletonMap("app", canaryName);
    }

    private static List<EnvVar> getEnvVar(ManagedKafka managedKafka) {
        List<EnvVar> envVars = new ArrayList<>(2);
        envVars.add(new EnvVarBuilder().withName("KAFKA_BOOTSTRAP_SERVERS").withValue(managedKafka.getMetadata().getName() + "-kafka-bootstrap:9092").build());
        envVars.add(new EnvVarBuilder().withName("RECONCILE_INTERVAL_MS").withValue("5000").build());
        return envVars;
    }

    private static List<ContainerPort> getContainerPorts() {
        return Collections.singletonList(new ContainerPortBuilder().withName("metrics").withContainerPort(8080).build());
    }

    public boolean isInstalling() {
        // TODO: logic for check if it's installing
        return false;
    }

    public boolean isReady() {
        // TODO: logic for check if it's ready
        return true;
    }

    public boolean isError() {
        // TODO: logic for check if it's error
        return false;
    }

    public Deployment getDeployment() {
        return deployment;
    }

    public void setDeployment(Deployment deployment) {
        this.deployment = deployment;
    }
}
