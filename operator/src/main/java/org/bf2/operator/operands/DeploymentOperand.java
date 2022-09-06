package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.Kafka;
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
            Kafka kafka = kafkaCluster.cachedKafka(managedKafka);
            // if we don't yet have a clusterId, then we've never been ready
            if (kafka == null || kafka.getStatus() == null || kafka.getStatus().getClusterId() == null) {
                return true;
            }
        }

        return false;
    }

}
