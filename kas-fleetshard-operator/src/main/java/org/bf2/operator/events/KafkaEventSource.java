package org.bf2.operator.events;

import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import io.strimzi.api.kafka.model.Kafka;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class KafkaEventSource extends AbstractEventSource implements ResourceEventHandler<Kafka> {

    @Inject
    Logger log;

    @Override
    public void onAdd(Kafka kafka) {
        log.infof("Add event received for Kafka %s/%s", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
        handleEvent(kafka);
    }

    @Override
    public void onUpdate(Kafka oldKafka, Kafka newKafka) {
        log.infof("Update event received for Kafka %s/%s", oldKafka.getMetadata().getNamespace(), oldKafka.getMetadata().getName());
        handleEvent(newKafka);
    }

    @Override
    public void onDelete(Kafka kafka, boolean deletedFinalStateUnknown) {
        log.infof("Delete event received for Kafka %s/%s", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
        handleEvent(kafka);
    }

    private void handleEvent(Kafka kafka) {
        eventHandler.handleEvent(new KafkaEvent(kafka, this));
    }
}
