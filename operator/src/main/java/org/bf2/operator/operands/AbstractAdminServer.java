package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.common.OperandUtils;
import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.jboss.logging.Logger;

import javax.inject.Inject;

import java.util.Optional;

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
        OperandUtils.createOrUpdate(kubernetesClient.apps().deployments(), deployment);
    }

    protected void createOrUpdate(Service service) {
        OperandUtils.createOrUpdate(kubernetesClient.services(), service);
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
    public OperandReadiness getReadiness(ManagedKafka managedKafka) {
        return getDeploymentReadiness(cachedDeployment(managedKafka), adminServerName(managedKafka));
    }

    static OperandReadiness getDeploymentReadiness(Deployment deployment, String name) {
        if (deployment == null) {
            return new OperandReadiness(Status.False, Reason.Installing, String.format("Deployment %s does not exist", name));
        }
        if (Readiness.isDeploymentReady(deployment)) {
            return new OperandReadiness(Status.True, null, null);
        }
        return Optional.ofNullable(deployment.getStatus())
                .map(DeploymentStatus::getConditions)
                .flatMap(l -> l.stream()
                        .filter(c -> "Progressing".equals(c.getType()))
                        .findAny()
                        .map(dc -> new OperandReadiness(Status.False,
                                "True".equals(dc.getStatus()) ? Reason.Installing : Reason.Error,
                                dc.getMessage())))
                .orElseGet(() -> new OperandReadiness(Status.False, Reason.Installing, String
                        .format("Deployment %s has no progressing condition", deployment.getMetadata().getName())));
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

    protected Deployment cachedDeployment(ManagedKafka managedKafka) {
        return informerManager.getLocalDeployment(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    protected Service cachedService(ManagedKafka managedKafka) {
        return informerManager.getLocalService(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    public static String adminServerName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-admin-server";
    }

    public static String adminServerConfigVolumeName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-config-volume";
    }

    public static String adminServerNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }
}
