package org.bf2.operator.operands;

import java.util.function.Predicate;

import javax.inject.Inject;

import org.bf2.operator.InformerManager;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.Context;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.status.Condition;

public abstract class AbstractKafkaCluster implements Operand<ManagedKafka> {

    @Inject
    Logger log;

    @Inject
    protected KafkaResourceClient kafkaResourceClient;

    @Inject
    protected KubernetesClient kubernetesClient;

    @Inject
    protected InformerManager informerManager;

    public static String kafkaClusterName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName();
    }

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        boolean isInstalling = kafka == null || kafka.getStatus() == null ||
                kafkaCondition(kafka, c->c.getType().equals("NotReady")
                && c.getStatus().equals("True")
                && c.getReason().equals("Creating"));
        log.debugf("KafkaCluster isInstalling = %s", isInstalling);
        return isInstalling;
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        boolean isReady = kafka != null && (kafka.getStatus() == null ||
                kafkaCondition(kafka, c->c.getType().equals("Ready") && c.getStatus().equals("True")));
        log.debugf("KafkaCluster isReady = %s", isReady);
        return isReady;
    }

    @Override
    public boolean isError(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        boolean isError = kafka != null && kafka.getStatus() != null
            && kafkaCondition(kafka, c->c.getType().equals("NotReady")
            && c.getStatus().equals("True")
            && !c.getReason().equals("Creating"));
        log.debugf("KafkaCluster isError = %s", isError);
        return isError;
    }

    protected boolean kafkaCondition(Kafka kafka, Predicate<Condition> predicate) {
        return kafka.getStatus().getConditions().stream().anyMatch(predicate);
    }

    protected Kafka cachedKafka(ManagedKafka managedKafka) {
        return informerManager.getLocalKafka(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
    }

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        Kafka current = cachedKafka(managedKafka);
        Kafka kafka = kafkaFrom(managedKafka, current);
        createOrUpdate(kafka);
    }

    protected abstract Kafka kafkaFrom(ManagedKafka managedKafka, Kafka current);

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kafkaResourceClient.delete(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
    }

    protected void createOrUpdate(Kafka kafka) {
        // Kafka resource doesn't exist, has to be created
        if (kafkaResourceClient.getByName(kafka.getMetadata().getNamespace(), kafka.getMetadata().getName()) == null) {
            log.infof("Creating Kafka instance %s/%s", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
            kafkaResourceClient.create(kafka);
        // Kafka resource already exists, has to be updated
        } else {
            log.infof("Updating Kafka instance %s", kafka.getSpec().getKafka().getVersion());
            kafkaResourceClient.patch(kafka);
        }
    }

}
