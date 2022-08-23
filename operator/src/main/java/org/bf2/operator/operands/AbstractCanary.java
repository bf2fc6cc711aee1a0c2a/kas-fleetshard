package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.jboss.logging.Logger;

import javax.inject.Inject;

public abstract class AbstractCanary implements Operand<ManagedKafka> {

    @Inject
    Logger log;

    @Inject
    protected KubernetesClient kubernetesClient;

    @Inject
    protected InformerManager informerManager;

    @Inject
    protected AbstractKafkaCluster kafkaCluster;

    public abstract Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current);

    public abstract Service serviceFrom(ManagedKafka managedKafka, Service current);

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        Deployment current = cachedDeployment(managedKafka);

        if (current == null) {
            OperandReadiness kafkaReadiness = kafkaCluster.getReadiness(managedKafka);
            if (kafkaReadiness.getStatus() != Status.True) {
                // do nothing as the kafka has never been ready
                return;
            }
        }

        Deployment deployment = deploymentFrom(managedKafka, current);
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
    public void delete(ManagedKafka managedKafka, Context context) {
        RollableScalableResource<Deployment> deploymentResource = canaryDeploymentResource(managedKafka);
        try {
            deploymentResource.scale(0, true);
            log.infof("Scaled down canary deployment: %s", managedKafka.getMetadata().getName());
        } catch (Exception e) {
            log.warnf("Ignored exception whilst scaling down canary deployment: %s", managedKafka.getMetadata().getName(), e);
        } finally {
            deploymentResource.delete();
        }
    }

    @Override
    public OperandReadiness getReadiness(ManagedKafka managedKafka) {
        return Operand.getDeploymentReadiness(cachedDeployment(managedKafka), canaryName(managedKafka));
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = cachedDeployment(managedKafka) == null;
        log.tracef("Canary isDeleted = %s", isDeleted);
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

    protected Service cachedService(ManagedKafka managedKafka) {
        return informerManager.getLocalService(canaryNamespace(managedKafka), canaryName(managedKafka));
    }

    protected RollableScalableResource<Deployment> canaryDeploymentResource(ManagedKafka managedKafka) {
        return kubernetesClient.apps()
                .deployments()
                .inNamespace(canaryNamespace(managedKafka))
                .withName(canaryName(managedKafka));
    }
}
