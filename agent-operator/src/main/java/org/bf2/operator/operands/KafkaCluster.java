package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListenersBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.status.KafkaStatus;
import io.strimzi.api.kafka.model.storage.EphemeralStorageBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides same functionalities to get a Kafka resource from a ManagedKafka one
 * and checking the corresponding status
 */
public class KafkaCluster {

    public static Kafka create(ManagedKafka managedKafka) {

        Map<String, Object> config = new HashMap<>();
        config.put("offsets.topic.replication.factor", 3);
        config.put("transaction.state.log.replication.factor", 3);
        config.put("transaction.state.log.min.isr", 2);
        config.put("log.message.format.version", managedKafka.getSpec().getVersions().getKafka());
        config.put("inter.broker.protocol.version", managedKafka.getSpec().getVersions().getKafka());

        Kafka kafka = new KafkaBuilder()
                .withNewApiVersion(Kafka.RESOURCE_GROUP + "/" + Kafka.V1BETA1)
                .withNewMetadata()
                    .withName(managedKafka.getMetadata().getName())
                .endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .withVersion(managedKafka.getSpec().getVersions().getKafka())
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

        // setting the ManagedKafka has owner of the Kafka resource is needed
        // by the operator sdk to handle events on the Kafka resource properly
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(managedKafka.getApiVersion())
                .withKind(managedKafka.getKind())
                .withName(managedKafka.getMetadata().getName())
                .withUid(managedKafka.getMetadata().getUid())
                .build();
        kafka.getMetadata().setOwnerReferences(Collections.singletonList(ownerReference));

        return kafka;
    }

    public static boolean isInstalling(KafkaStatus status) {
        Condition kafkaCondition = status.getConditions().get(0);
        return kafkaCondition.getType().equals("NotReady")
                && kafkaCondition.getStatus().equals("True")
                && kafkaCondition.getReason().equals("Creating");
    }

    public static boolean isReady(KafkaStatus status) {
        Condition kafkaCondition = status.getConditions().get(0);
        return kafkaCondition.getType().equals("Ready") && kafkaCondition.getStatus().equals("True");
    }

    public static boolean isError(KafkaStatus status) {
        Condition kafkaCondition = status.getConditions().get(0);
        return kafkaCondition.getType().equals("NotReady")
                && kafkaCondition.getStatus().equals("True")
                && !kafkaCondition.getReason().equals("Creating");
    }

}
