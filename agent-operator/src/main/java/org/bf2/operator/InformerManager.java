package org.bf2.operator;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.events.DeploymentEventSource;
import org.bf2.operator.events.KafkaEventSource;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

@ApplicationScoped
public class InformerManager {

    @Inject
    private KubernetesClient client;

    @Inject
    private KafkaEventSource kafkaEventSource;
    @Inject
    private DeploymentEventSource deploymentEventSource;

    SharedInformerFactory sharedInformerFactory;

    void onStart(@Observes StartupEvent ev) {
        sharedInformerFactory = client.informers();

        // TODO: should we make the resync time configurable?
        SharedIndexInformer<Kafka> kafkaSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Kafka.class, KafkaList.class, 60 * 1000L);
        // TODO: should we be informed for specific Deployments based on labels?
        SharedIndexInformer<Deployment> deploymentSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Deployment.class, DeploymentList.class, 60 * 1000L);

        kafkaSharedIndexInformer.addEventHandler(kafkaEventSource);
        deploymentSharedIndexInformer.addEventHandler(deploymentEventSource);

        sharedInformerFactory.startAllRegisteredInformers();
    }

    void onStop(@Observes ShutdownEvent ev) {
        sharedInformerFactory.stopAllRegisteredInformers();
    }
}
