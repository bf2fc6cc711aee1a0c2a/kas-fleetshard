package org.bf2.operator;

import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaEventSource extends AbstractEventSource implements Watcher<Kafka> {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventSource.class);

    private MixedOperation<Kafka, KafkaList, Resource<Kafka>> kafkaClient;

    private KafkaEventSource(MixedOperation<Kafka, KafkaList, Resource<Kafka>> kafkaClient) {
        this.kafkaClient = kafkaClient;
    }

    public static KafkaEventSource createAndRegisterWatch(MixedOperation<Kafka, KafkaList, Resource<Kafka>> kafkaClient) {
        KafkaEventSource kafkaEventSource = new KafkaEventSource(kafkaClient);
        kafkaEventSource.registerWatch();
        return kafkaEventSource;
    }

    private void registerWatch() {
        kafkaClient
                .inAnyNamespace()
                .watch(this);
    }

    @Override
    public void eventReceived(Action action, Kafka kafka) {
        log.info("Kafka event received: action {} kafka {}/{}", action, kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
        if (action == Action.ERROR) {
            log.warn("Skipping Kafka event: action {} kafka {}/{}", action, kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
            return;
        }
        eventHandler.handleEvent(new KafkaEvent(action, kafka, this));
    }

    @Override
    public void onClose(WatcherException e) {
        if (e == null) {
            return;
        }
        if (e.isHttpGone()) {
            log.warn("Received error for watch, will try to reconnect.", e);
            registerWatch();
        } else {
            log.error("Unexpected error happened with watch. Will exit.", e);
            System.exit(1);
        }
    }
}
