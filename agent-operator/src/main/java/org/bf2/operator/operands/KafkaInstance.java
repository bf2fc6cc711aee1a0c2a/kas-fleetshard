package org.bf2.operator.operands;

import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * Represents an overall Kafka instance made by Kafka, Canary and AdminServer resources
 */
@ApplicationScoped
public class KafkaInstance implements Operand<ManagedKafka> {

    @Inject
    private KafkaCluster kafkaCluster;
    @Inject
    private Canary canary;
    @Inject
    private AdminServer adminServer;

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        kafkaCluster.createOrUpdate(managedKafka);
        canary.createOrUpdate(managedKafka);
        adminServer.createOrUpdate(managedKafka);
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kafkaCluster.delete(managedKafka, context);
        canary.delete(managedKafka, context);
        adminServer.delete(managedKafka, context);
    }

    @Override
    public boolean isInstalling() {
        // the check is done in a kind of priority: 1. Kafka, 2. Canary 3. Admin Server
        // if the current one is installing we don't mind to check the others
        return kafkaCluster.isInstalling() ||
                canary.isInstalling() ||
                adminServer.isInstalling();
    }

    @Override
    public boolean isReady() {
        // the check is done in a kind of priority: 1. Kafka, 2. Canary 3. Admin Server
        // if the current one is not ready we don't mind to check the others
        if (!kafkaCluster.isReady()) {
            return false;
        }
        if (!canary.isReady()) {
            return false;
        }
        if (!adminServer.isReady()) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isError() {
        // the check is done in a kind of priority: 1. Kafka, 2. Canary 3. Admin Server
        // if the current one is in error we don't mind to check the others
        if (kafkaCluster.isError()) {
            return true;
        }
        if (canary.isError()) {
            return true;
        }
        if (adminServer.isError()) {
            return true;
        }
        return false;
    }

    public KafkaCluster getKafkaCluster() {
        return kafkaCluster;
    }

    public Canary getCanary() {
        return canary;
    }

    public AdminServer getAdminServer() {
        return adminServer;
    }
}
