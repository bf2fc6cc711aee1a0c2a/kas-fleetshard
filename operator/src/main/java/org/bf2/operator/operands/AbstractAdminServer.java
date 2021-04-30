package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.jboss.logging.Logger;

import javax.inject.Inject;

public abstract class AbstractAdminServer implements Operand<ManagedKafka> {

    @Inject
    Logger log;

    @Inject
    protected KubernetesClient kubernetesClient;

    @Inject
    protected InformerManager informerManager;

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        Deployment currentDeployment = cachedDeployment(managedKafka);
        Deployment deployment = deploymentFrom(managedKafka, currentDeployment);
        createOrUpdate(deployment);

        Service currentService = cachedService(managedKafka);
        Service service = serviceFrom(managedKafka, currentService);
        createOrUpdate(service);
    }

    protected void createOrUpdate(Deployment deployment) {
        // Admin Server deployment resource doesn't exist, has to be created
        if (kubernetesClient.apps()
                .deployments()
                .inNamespace(deployment.getMetadata().getNamespace())
                .withName(deployment.getMetadata().getName())
                .get() == null) {
            kubernetesClient.apps()
                    .deployments()
                    .inNamespace(deployment.getMetadata().getNamespace())
                    .create(deployment);
            // Admin Server deployment resource already exists, has to be updated
        } else {
            kubernetesClient.apps()
                    .deployments()
                    .inNamespace(deployment.getMetadata().getNamespace())
                    .withName(deployment.getMetadata().getName())
                    .patch(deployment);
        }
    }

    protected void createOrUpdate(Service service) {
        // Admin Server service resource doesn't exist, has to be created
        if (kubernetesClient.services()
                .inNamespace(service.getMetadata().getNamespace())
                .withName(service.getMetadata().getName())
                .get() == null) {
            kubernetesClient.services().inNamespace(service.getMetadata().getNamespace()).create(service);
            // Admin Server service resource already exists, has to be updated
        } else {
            kubernetesClient.services()
                    .inNamespace(service.getMetadata().getNamespace())
                    .withName(service.getMetadata().getName())
                    .patch(service);
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        adminDeploymentResource(managedKafka).delete();
        adminServiceResource(managedKafka).delete();
    }

    protected abstract Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current);

    protected abstract Service serviceFrom(ManagedKafka managedKafka, Service current);

    public abstract String uri(ManagedKafka managedKafka);

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        Deployment deployment = cachedDeployment(managedKafka);
        boolean isInstalling = deployment == null || deployment.getStatus() == null;
        log.tracef("Admin Server isInstalling = %s", isInstalling);
        return isInstalling;
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        Deployment deployment = cachedDeployment(managedKafka);
        boolean isReady = deployment != null && (deployment.getStatus() == null ||
                (deployment.getStatus().getReadyReplicas() != null
                        && deployment.getStatus().getReadyReplicas().equals(deployment.getSpec().getReplicas())));
        log.tracef("Admin Server isReady = %s", isReady);
        return isReady;
    }

    @Override
    public boolean isError(ManagedKafka managedKafka) {
        // TODO: logic for check if it's error
        return false;
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = cachedDeployment(managedKafka) == null && cachedService(managedKafka) == null;
        log.tracef("Admin Server isDeleted = %s", isDeleted);
        return isDeleted;
    }

    protected Resource<Service> adminServiceResource(ManagedKafka managedKafka) {
        return kubernetesClient.services()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerName(managedKafka));
    }

    protected Resource<Deployment> adminDeploymentResource(ManagedKafka managedKafka) {
        return kubernetesClient.apps()
                .deployments()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerName(managedKafka));
    }

    protected Deployment cachedDeployment(ManagedKafka managedKafka) {
        return informerManager.getLocalDeployment(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    protected Service cachedService(ManagedKafka managedKafka) {
        return informerManager.getLocalService(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    public static String adminServerName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-admin-server";
    }

    public static String adminServerNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }
}
