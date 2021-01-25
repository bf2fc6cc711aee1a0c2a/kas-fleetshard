package org.bf2.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

@ApplicationScoped
public class InformerManager {

    @Inject
    private KubernetesClient client;

    @Inject
    private KafkaEventSource kafkaEventSource;

    SharedInformerFactory sharedInformerFactory;

    void onStart(@Observes StartupEvent ev) {
        sharedInformerFactory = client.informers();

        SharedIndexInformer<Kafka> kafkaSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Kafka.class, KafkaList.class, 60 * 1000L);

        kafkaSharedIndexInformer.addEventHandler(kafkaEventSource);

        sharedInformerFactory.startAllRegisteredInformers();
    }

    void onStop(@Observes ShutdownEvent ev) {
        sharedInformerFactory.stopAllRegisteredInformers();
    }
}
