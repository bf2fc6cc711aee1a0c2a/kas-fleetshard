package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

/**
 * Represents an overall Kafka instance made by Kafka, Canary and AdminServer
 */
public class KafkaInstance {

    private Kafka kafka;
    private Deployment canary;
    private Deployment adminServer;

    private KafkaInstance(Kafka kafka, Deployment canary, Deployment adminServer) {
        this.kafka = kafka;
        this.canary = canary;
        this.adminServer = adminServer;
    }

    /**
     * Create a KafkaInstance from a ManagedKafka resource getting a Kafka resource
     * and Canary and AdminServer deployments from the corresponding classes
     *
     * @param managedKafka ManagedKafka resource from which create a KafkaInstance
     * @return a KafkaInstance holding Kafka resource and Canary and AdminServer deployments
     */
    public static KafkaInstance create(ManagedKafka managedKafka) {
        Kafka kafka = KafkaCluster.getKafka(managedKafka);
        Deployment canary = Canary.getDeployment(managedKafka);
        Deployment adminServer = AdminServer.getDeployment(managedKafka);
        return new KafkaInstance(kafka, canary, adminServer);
    }

    public void update(ManagedKafka managedKafka) {
        // TODO: logic to update the underlying Kafka, Canary and AdminServer
    }

    /**
     * @return if the Kafka instance is still installling
     */
    public boolean isInstalling() {
        // the check is done in a kind of priority: 1. Kafka, 2. Canary 3. Admin Server
        // if the current one is installing we don't mind to check the others
        return KafkaCluster.isInstalling(kafka.getStatus()) ||
                Canary.isInstalling(canary.getStatus()) ||
                AdminServer.isInstalling(adminServer.getStatus());
    }

    /**
     * @return @return if the Kafka instance is ready to be used
     */
    public boolean isReady() {
        // the check is done in a kind of priority: 1. Kafka, 2. Canary 3. Admin Server
        // if the current one is installing we don't mind to check the others
        if (!KafkaCluster.isReady(kafka.getStatus())) {
            return false;
        }
        if (!Canary.isReady(canary.getStatus())) {
            return false;
        }
        if (!AdminServer.isReady(adminServer.getStatus())) {
            return false;
        }
        return true;
    }

    /**
     * @return @return if the Kafka instance is in an error state
     */
    public boolean isError() {
        // the check is done in a kind of priority: 1. Kafka, 2. Canary 3. Admin Server
        // if the current one is installing we don't mind to check the others
        if (!KafkaCluster.isError(kafka.getStatus())) {
            return false;
        }
        if (!Canary.isError(canary.getStatus())) {
            return false;
        }
        if (!AdminServer.isError(adminServer.getStatus())) {
            return false;
        }
        return true;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Deployment getCanary() {
        return canary;
    }

    public void setCanary(Deployment canary) {
        this.canary = canary;
    }

    public Deployment getAdminServer() {
        return adminServer;
    }

    public void setAdminServer(Deployment adminServer) {
        this.adminServer = adminServer;
    }
}
