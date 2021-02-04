package org.bf2.operator;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.events.DeploymentEventSource;
import org.bf2.operator.events.KafkaEventSource;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.Collections;

@ApplicationScoped
public class InformerManager {

    @Inject
    private KubernetesClient client;

    @Inject
    private KafkaEventSource kafkaEventSource;
    @Inject
    private DeploymentEventSource deploymentEventSource;

    private SharedInformerFactory sharedInformerFactory;

    private SharedIndexInformer<Kafka> kafkaSharedIndexInformer;
    private SharedIndexInformer<Deployment> deploymentSharedIndexInformer;


    void onStart(@Observes StartupEvent ev) {
        sharedInformerFactory = client.informers();

        OperationContext operationContext =
                new OperationContext().withLabels(Collections.singletonMap("app.kubernetes.io/managed-by", "fleetshard-operator"));

        // TODO: should we make the resync time configurable?
        kafkaSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Kafka.class, KafkaList.class, operationContext, 60 * 1000L);

        deploymentSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Deployment.class, DeploymentList.class, operationContext, 60 * 1000L);

        kafkaSharedIndexInformer.addEventHandler(kafkaEventSource);
        deploymentSharedIndexInformer.addEventHandler(deploymentEventSource);

        sharedInformerFactory.startAllRegisteredInformers();
    }

    void onStop(@Observes ShutdownEvent ev) {
        sharedInformerFactory.stopAllRegisteredInformers();
    }

    public Kafka getLocalKafka(String namespace, String name) {
        return kafkaSharedIndexInformer.getIndexer().getByKey(Cache.namespaceKeyFunc(namespace, name));
    }

    public Deployment getLocalDeployment(String namespace, String name) {
        return deploymentSharedIndexInformer.getIndexer().getByKey(Cache.namespaceKeyFunc(namespace, name));
    }
}
