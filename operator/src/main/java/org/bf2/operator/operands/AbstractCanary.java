package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.jboss.logging.Logger;

import javax.inject.Inject;

public abstract class AbstractCanary implements Operand<ManagedKafka> {

    @Inject
    Logger log;

    @Inject
    protected KubernetesClient kubernetesClient;

    @Inject
    protected InformerManager informerManager;

    protected abstract Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current);

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        Deployment current = cachedDeployment(managedKafka);
        Deployment deployment = deploymentFrom(managedKafka, current);
        createOrUpdate(deployment);
    }

    protected void createOrUpdate(Deployment deployment) {
        // Canary deployment resource doesn't exist, has to be created
        if (kubernetesClient.apps().deployments()
                .inNamespace(deployment.getMetadata().getNamespace())
                .withName(deployment.getMetadata().getName()).get() == null) {
            kubernetesClient.apps().deployments().inNamespace(deployment.getMetadata().getNamespace()).create(deployment);
        // Canary deployment resource already exists, has to be updated
        } else {
            kubernetesClient.apps().deployments()
                    .inNamespace(deployment.getMetadata().getNamespace())
                    .withName(deployment.getMetadata().getName())
                    .patch(deployment);
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        canaryDeploymentResource(managedKafka).delete();
    }

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        Deployment deployment = cachedDeployment(managedKafka);
        boolean isInstalling = deployment == null || deployment.getStatus() == null;
        log.debugf("Canary isInstalling = %s", isInstalling);
        return isInstalling;
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        Deployment deployment = cachedDeployment(managedKafka);
        boolean isReady = deployment != null && (deployment.getStatus() == null ||
                (deployment.getStatus().getReadyReplicas() != null && deployment.getStatus().getReadyReplicas().equals(deployment.getSpec().getReplicas())));
        log.debugf("Canary isReady = %s", isReady);
        return isReady;
    }

    @Override
    public boolean isError(ManagedKafka managedKafka) {
        // TODO: logic for check if it's error
        return false;
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = cachedDeployment(managedKafka) == null;
        log.debugf("Canary isDeleted = %s", isDeleted);
        return isDeleted;
    }

    public static String canaryName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-canary";
    }

    public static String canaryNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    protected Deployment cachedDeployment(ManagedKafka managedKafka) {
        return informerManager.getLocalDeployment(canaryNamespace(managedKafka), canaryName(managedKafka));
    }

    protected Resource<Deployment> canaryDeploymentResource(ManagedKafka managedKafka) {
        return kubernetesClient.apps()
                .deployments()
                .inNamespace(canaryNamespace(managedKafka))
                .withName(canaryName(managedKafka));
    }
}
