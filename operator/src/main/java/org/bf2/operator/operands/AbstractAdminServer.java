package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.InformerManager;
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

        Service currentAdminServerService = cachedAdminServerService(managedKafka);
        Service adminServerService = adminServerServiceFrom(managedKafka, currentAdminServerService);
        createOrUpdate(adminServerService);

        Service currentEnvoyService = cachedEnvoyService(managedKafka);
        Service envoyService = envoyServiceFrom(managedKafka, currentEnvoyService);
        createOrUpdate(envoyService);
    }

    protected void createOrUpdate(Deployment deployment) {
        OperandUtils.createOrUpdate(kubernetesClient.apps().deployments(), deployment);
    }

    protected void createOrUpdate(Service service) {
        OperandUtils.createOrUpdate(kubernetesClient.services(), service);
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        adminDeploymentResource(managedKafka).delete();
        adminServiceResource(managedKafka).delete();
        envoyServiceResource(managedKafka).delete();
    }

    public abstract Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current);

    public abstract Service adminServerServiceFrom(ManagedKafka managedKafka, Service current);

    public abstract Service envoyServiceFrom(ManagedKafka managedKafka, Service current);

    public abstract String uri(ManagedKafka managedKafka);

    @Override
    public OperandReadiness getReadiness(ManagedKafka managedKafka) {
        return Operand.getDeploymentReadiness(cachedDeployment(managedKafka), adminServerName(managedKafka));
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = cachedDeployment(managedKafka) == null
                && cachedAdminServerService(managedKafka) == null
                && cachedEnvoyService(managedKafka) == null;

        log.tracef("Admin Server isDeleted = %s", isDeleted);
        return isDeleted;
    }

    protected Resource<Service> adminServiceResource(ManagedKafka managedKafka) {
        return kubernetesClient.services()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerName(managedKafka));
    }

    protected Resource<Service> envoyServiceResource(ManagedKafka managedKafka) {
        return kubernetesClient.services()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerEnvoyName(managedKafka));
    }

    protected Resource<Deployment> adminDeploymentResource(ManagedKafka managedKafka){
        return kubernetesClient.apps().deployments()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerName(managedKafka));
    }

    protected Deployment cachedDeployment(ManagedKafka managedKafka) {
        return informerManager.getLocalDeployment(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    protected Service cachedAdminServerService(ManagedKafka managedKafka) {
        return informerManager.getLocalService(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    protected Service cachedEnvoyService(ManagedKafka managedKafka) {
        return informerManager.getLocalService(adminServerNamespace(managedKafka), adminServerEnvoyName(managedKafka));
    }

    public static String adminServerName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-admin-server";
    }

    public static String adminServerEnvoyName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-admin-server-envoy";
    }

    public static String adminServerNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }
}
