package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
public class Canary implements Operand<ManagedKafka> {

    private static final Logger log = LoggerFactory.getLogger(Canary.class);

    @Inject
    private KubernetesClient kubernetesClient;

    private Deployment deployment;

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        String canaryName = canaryName(managedKafka);

        deployment = deploymentFrom(managedKafka);
        // Canary deployment resource doesn't exist, has to be created
        if (kubernetesClient.apps().deployments().inNamespace(managedKafka.getMetadata().getNamespace()).withName(canaryName).get() == null) {
            kubernetesClient.apps().deployments().inNamespace(managedKafka.getMetadata().getNamespace()).create(deployment);
        // Canary deployment resource already exists, has to be updated
        } else {
            kubernetesClient.apps().deployments().inNamespace(managedKafka.getMetadata().getNamespace()).createOrReplace(deployment);
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kubernetesClient.apps()
                .deployments()
                .inNamespace(managedKafka.getMetadata().getNamespace())
                .withName(canaryName(managedKafka))
                .delete();
    }

    private Deployment deploymentFrom(ManagedKafka managedKafka) {

        String canaryName = canaryName(managedKafka);

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(canaryName)
                    .withNamespace(managedKafka.getMetadata().getNamespace())
                    .withLabels(getLabels(canaryName))
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

        // setting the ManagedKafka has owner of the Canary deployment resource is needed
        // by the operator sdk to handle events on the Deployment resource properly
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(managedKafka.getApiVersion())
                .withKind(managedKafka.getKind())
                .withName(managedKafka.getMetadata().getName())
                .withUid(managedKafka.getMetadata().getUid())
                .build();
        deployment.getMetadata().setOwnerReferences(Collections.singletonList(ownerReference));

        return deployment;
    }

    private static Map<String, String> getLabels(String canaryName) {
        // TODO: adding label about observability
        Map<String, String> labels = new HashMap<>(2);
        labels.put("app", canaryName);
        labels.put("app.kubernetes.io/managed-by", "agent-operator");
        return labels;
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

    @Override
    public boolean isInstalling() {
        // TODO: check replicas are not available because of an error
        boolean isInstalling = deployment.getStatus() == null ||
                (deployment.getStatus().getUnavailableReplicas() != null && deployment.getStatus().getUnavailableReplicas().equals(deployment.getSpec().getReplicas()));
        log.info("Canary isInstalling = {}", isInstalling);
        return isInstalling;
    }

    @Override
    public boolean isReady() {
        boolean isReady = deployment.getStatus() == null ||
                (deployment.getStatus().getReadyReplicas() != null && deployment.getStatus().getReadyReplicas().equals(deployment.getSpec().getReplicas()));
        log.info("Canary isReady = {}", isReady);
        return isReady;
    }

    @Override
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

    public static String canaryName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-canary";
    }
}
