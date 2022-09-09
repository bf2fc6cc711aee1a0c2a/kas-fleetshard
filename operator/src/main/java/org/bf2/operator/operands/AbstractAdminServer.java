package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.jboss.logging.Logger;

import javax.inject.Inject;

public abstract class AbstractAdminServer extends DeploymentOperand {

    @Inject
    Logger log;

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

    @Override
    public void delete(ManagedKafka managedKafka, Context context) {
        adminDeploymentResource(managedKafka).delete();
        adminServiceResource(managedKafka).delete();
    }

    public abstract Service serviceFrom(ManagedKafka managedKafka, Service current);

    public abstract String uri(ManagedKafka managedKafka);

    @Override
    public OperandReadiness getReadiness(ManagedKafka managedKafka) {
        return Operand.getDeploymentReadiness(cachedDeployment(managedKafka), adminServerName(managedKafka));
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

    protected Resource<Deployment> adminDeploymentResource(ManagedKafka managedKafka){
        return kubernetesClient.apps().deployments()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerName(managedKafka));
    }

    @Override
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
