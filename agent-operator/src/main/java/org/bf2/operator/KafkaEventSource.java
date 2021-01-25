package org.bf2.operator;

import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KafkaEventSource extends AbstractEventSource implements ResourceEventHandler<Kafka> {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventSource.class);

    @Override
    public void onAdd(Kafka kafka) {
        log.info("Add event receiving for Kafka {}/{}", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
        handleEvent(kafka);
    }

    @Override
    public void onUpdate(Kafka oldKafka, Kafka newKafka) {
        log.info("Update event receiving for Kafka {}/{}", oldKafka.getMetadata().getNamespace(), oldKafka.getMetadata().getName());
        handleEvent(newKafka);
    }

    @Override
    public void onDelete(Kafka kafka, boolean deletedFinalStateUnknown) {
        log.info("Delete event receiving for Kafka {}/{}", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
        handleEvent(kafka);
    }

    private void handleEvent(Kafka kafka) {
        eventHandler.handleEvent(new KafkaEvent(kafka, this));
    }
}
