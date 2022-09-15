package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.inject.Inject;

import java.util.Objects;

public abstract class DeploymentOperand implements Operand<ManagedKafka> {

    @Inject
    protected KubernetesClient kubernetesClient;

    @Inject
    protected AbstractKafkaCluster kafkaCluster;

    protected abstract Deployment cachedDeployment(ManagedKafka managedKafka);

    public abstract Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current);

    protected void createOrUpdate(Deployment deployment) {
        OperandUtils.createOrUpdate(kubernetesClient.apps().deployments(), deployment);
    }

    protected void createOrUpdate(Service service) {
        OperandUtils.createOrUpdate(kubernetesClient.services(), service);
    }

    protected boolean handleReserveOrWaitForKafka(ManagedKafka managedKafka) {
        Deployment current = cachedDeployment(managedKafka);

        if (managedKafka.isReserveDeployment()) {
            Deployment deployment = deploymentFrom(managedKafka, null);

            deployment = ReservedDeploymentConverter.asReservedDeployment(current, deployment, managedKafka);
            if (!Objects.equals(current, deployment)) {
                createOrUpdate(deployment);
            }
            return true;
        }

        // if not yet created, wait until kafka is ready
        return current == null && !kafkaCluster.hasKafkaBeenReady(managedKafka);
    }

}
