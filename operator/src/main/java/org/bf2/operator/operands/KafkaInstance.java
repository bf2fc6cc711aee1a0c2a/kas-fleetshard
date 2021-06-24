package org.bf2.operator.operands;

import io.javaoperatorsdk.operator.api.Context;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.StrimziManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.secrets.ImagePullSecretManager;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an overall Kafka instance made by Kafka, Canary and AdminServer resources
 */
@ApplicationScoped
public class KafkaInstance implements Operand<ManagedKafka, ManagedKafkaCondition> {

    @Inject
    AbstractKafkaCluster kafkaCluster;
    @Inject
    Canary canary;
    @Inject
    AdminServer adminServer;
    @Inject
    ImagePullSecretManager imagePullSecretManager;

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        imagePullSecretManager.propagateSecrets(managedKafka);

        kafkaCluster.createOrUpdate(managedKafka);
        canary.createOrUpdate(managedKafka);
        adminServer.createOrUpdate(managedKafka);
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        imagePullSecretManager.deleteSecrets(managedKafka);

        kafkaCluster.delete(managedKafka, context);
        canary.delete(managedKafka, context);
        adminServer.delete(managedKafka, context);
    }

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        // the check is done in a kind of priority: 1. Kafka, 2. Canary 3. Admin Server
        // if the current one is installing we don't mind to check the others
        return kafkaCluster.isInstalling(managedKafka) ||
                canary.isInstalling(managedKafka) ||
                adminServer.isInstalling(managedKafka);
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        // the check is done in a kind of priority: 1. Kafka, 2. Canary 3. Admin Server
        // if the current one is not ready we don't mind to check the others
        if (!kafkaCluster.isReady(managedKafka)) {
            return false;
        }
        if (!canary.isReady(managedKafka)) {
            return false;
        }
        if (!adminServer.isReady(managedKafka)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isError(ManagedKafka managedKafka) {
        // the check is done in a kind of priority: 1. Kafka, 2. Canary 3. Admin Server
        // if the current one is in error we don't mind to check the others
        if (kafkaCluster.isError(managedKafka)) {
            return true;
        }
        if (canary.isError(managedKafka)) {
            return true;
        }
        if (adminServer.isError(managedKafka)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        return kafkaCluster.isDeleted(managedKafka)
                && canary.isDeleted(managedKafka)
                && adminServer.isDeleted(managedKafka);
    }

    @Override
    public List<ManagedKafkaCondition> validate(ManagedKafka managedKafka) {
        List<ManagedKafkaCondition> warningConditions = new ArrayList<>(0);
        warningConditions.addAll(kafkaCluster.validate(managedKafka));
        warningConditions.addAll(canary.validate(managedKafka));
        warningConditions.addAll(adminServer.validate(managedKafka));
        return warningConditions;
    }

    public boolean isStrimziUpdating(ManagedKafka managedKafka) {
        Kafka kafka =  kafkaCluster.cachedKafka(managedKafka);
        String pauseReason = kafka.getMetadata().getAnnotations() != null ?
                kafka.getMetadata().getAnnotations().get(StrimziManager.STRIMZI_PAUSE_REASON_ANNOTATION) : null;
        return ManagedKafkaCondition.Reason.StrimziUpdating.name().toLowerCase().equals(pauseReason) &&
                kafkaCluster.isReconciliationPaused(managedKafka);
    }

    public AbstractKafkaCluster getKafkaCluster() {
        return kafkaCluster;
    }

    public Canary getCanary() {
        return canary;
    }

    public AdminServer getAdminServer() {
        return adminServer;
    }
}
