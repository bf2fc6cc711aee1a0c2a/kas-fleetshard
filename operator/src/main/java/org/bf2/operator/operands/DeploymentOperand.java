package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;

import javax.inject.Inject;

import java.util.Objects;

public abstract class DeploymentOperand implements Operand<ManagedKafka> {

    @Inject
    protected KubernetesClient kubernetesClient;

    @Inject
    protected AbstractKafkaCluster kafkaCluster;

    protected abstract Deployment cachedDeployment(ManagedKafka managedKafka);

    protected void createOrUpdate(Deployment deployment) {
        OperandUtils.createOrUpdate(kubernetesClient.apps().deployments(), deployment);
    }

    protected void createOrUpdate(Service service) {
        OperandUtils.createOrUpdate(kubernetesClient.services(), service);
    }

    public abstract Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current);

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

        if (current == null) {
            OperandReadiness kafkaReadiness = kafkaCluster.getReadiness(managedKafka);
            if (kafkaReadiness.getStatus() != Status.True) {
                // don't create until ready
                return true;
            }
        }

        return false;
    }

}
