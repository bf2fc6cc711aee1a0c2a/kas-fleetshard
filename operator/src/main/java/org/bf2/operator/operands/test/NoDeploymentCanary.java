package org.bf2.operator.operands.test;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.Context;
import io.quarkus.arc.properties.IfBuildProperty;
import org.bf2.operator.operands.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.enterprise.context.ApplicationScoped;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 * For testing purpose only, it puts the Deployment declaration into a ConfigMap so the actual pod is not created
 */
@ApplicationScoped
@IfBuildProperty(name = "kafka", stringValue = "test")
public class NoDeploymentCanary extends org.bf2.operator.operands.Canary {

    @Override
    protected void createOrUpdate(Deployment deployment) {
        // Canary deployment resource doesn't exist, has to be created
        if (kubernetesClient.configMaps()
                .inNamespace(deployment.getMetadata().getNamespace())
                .withName(deployment.getMetadata().getName()).get() == null) {

            ConfigMap cm = new ConfigMapBuilder()
                    .withNewMetadata()
                        .withNamespace(deployment.getMetadata().getNamespace())
                        .withName(deployment.getMetadata().getName())
                        .withLabels(OperandUtils.getDefaultLabels())
                        .withOwnerReferences(deployment.getMetadata().getOwnerReferences())
                    .endMetadata()
                    .withData(Collections.singletonMap("deployment", Serialization.asYaml(deployment)))
                    .build();

            kubernetesClient.configMaps().inNamespace(deployment.getMetadata().getNamespace()).create(cm);
        // Canary deployment resource already exists, has to be updated
        } else {
            ConfigMap cm = kubernetesClient.configMaps()
                    .inNamespace(deployment.getMetadata().getNamespace())
                    .withName(deployment.getMetadata().getName()).get();

            kubernetesClient.configMaps()
                    .inNamespace(deployment.getMetadata().getNamespace())
                    .withName(deployment.getMetadata().getName())
                    .patch(cm);
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kubernetesClient.configMaps()
                .inNamespace(canaryNamespace(managedKafka))
                .withName(canaryName(managedKafka))
                .delete();
    }

    @Override
    protected Deployment cachedDeployment(ManagedKafka managedKafka) {
        ConfigMap cm = informerManager.getLocalConfigMap(canaryNamespace(managedKafka), canaryName(managedKafka));
        if (cm == null) {
            return null;
        }
        InputStream is = new ByteArrayInputStream(cm.getData().get("deployment").getBytes(StandardCharsets.UTF_8));
        return kubernetesClient.apps().deployments().load(is).get();
    }
}
