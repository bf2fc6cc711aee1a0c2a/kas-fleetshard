package org.bf2.operator;

import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListenersBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.storage.EphemeralStorageBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import java.util.HashMap;
import java.util.Map;

public class KafkaFactory {

    public static Kafka getKafka(ManagedKafka managedKafka) {

        Map<String, Object> config = new HashMap<>();
        config.put("offsets.topic.replication.factor", 3);
        config.put("transaction.state.log.replication.factor", 3);
        config.put("transaction.state.log.min.isr", 2);
        config.put("log.message.format.version", "2.6");
        config.put("inter.broker.protocol.version", "2.6");

        Kafka kafka = new KafkaBuilder()
                .withNewApiVersion(Kafka.RESOURCE_GROUP + "/" + Kafka.V1BETA1)
                .withNewMetadata()
                .withName(managedKafka.getMetadata().getName())
                .endMetadata()
                .withNewSpec()
                .withNewKafka()
                .withVersion(managedKafka.getSpec().getKafkaInstance().getVersion())
                .withReplicas(3)
                .withListeners(
                        new ArrayOrObjectKafkaListenersBuilder()
                                .withGenericKafkaListeners(
                                        new GenericKafkaListenerBuilder()
                                                .withName("plain")
                                                .withPort(9092)
                                                .withType(KafkaListenerType.INTERNAL)
                                                .withTls(false)
                                                .build()
                                ).build()
                )
                .withStorage(new EphemeralStorageBuilder().build())
                .withConfig(config)
                .endKafka()
                .withNewZookeeper()
                .withReplicas(3)
                .withStorage(new EphemeralStorageBuilder().build())
                .endZookeeper()
                .endSpec()
                .build();

        return kafka;
    }

}
