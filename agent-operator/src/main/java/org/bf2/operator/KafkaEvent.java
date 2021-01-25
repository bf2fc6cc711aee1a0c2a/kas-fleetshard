package org.bf2.operator;

import io.fabric8.kubernetes.client.Watcher;
import io.javaoperatorsdk.operator.processing.event.AbstractEvent;
import io.strimzi.api.kafka.model.Kafka;

public class KafkaEvent extends AbstractEvent {

    private Kafka kafka;

    public KafkaEvent(Kafka kafka, KafkaEventSource kafkaEventSource) {
        super(kafka.getMetadata().getOwnerReferences().get(0).getUid(), kafkaEventSource);
        this.kafka = kafka;
    }

    public Kafka getKafka() {
        return kafka;
    }
}
