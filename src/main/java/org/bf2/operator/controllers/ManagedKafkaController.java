package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.javaoperatorsdk.operator.api.*;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.KafkaFactory;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

@Controller(crdName = "managedkafkas.managedkafka.bf2.org", name = "ManagedKafkaController")
public class ManagedKafkaController implements ResourceController<ManagedKafka> {

    private static final Logger log = LoggerFactory.getLogger(ManagedKafkaController.class);

    @Inject
    private KubernetesClient client;

    private MixedOperation<Kafka, KafkaList, DoneableKafka, Resource<Kafka, DoneableKafka>> kafkaClient;

    @Override
    public DeleteControl deleteResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        log.info("Deleting Kafka instance {}", managedKafka.getMetadata().getName());

        kafkaClient
                .inNamespace(managedKafka.getMetadata().getNamespace())
                .withName(managedKafka.getMetadata().getName())
                .delete();

        return DeleteControl.DEFAULT_DELETE;
    }

    @Override
    public UpdateControl<ManagedKafka> createOrUpdateResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        log.info("Creating Kafka instance version {}", managedKafka.getSpec().getKafkaInstance().getVersion());

        Kafka kafka = KafkaFactory.getKafka(managedKafka);
        log.info("Creating Kafka = {}", kafka);
        try {
            kafkaClient.create(kafka);
        } catch (Exception e) {
            log.error("Error creating the Kafka instance", e);
        }
        return UpdateControl.noUpdate();
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        log.info("init");

        CustomResourceDefinition kafkaCrd = Crds.kafka();
        CustomResourceDefinitionContext kafkaCrdContext = CustomResourceDefinitionContext.fromCrd(kafkaCrd);
        kafkaClient = client.customResources(kafkaCrdContext, Kafka.class, KafkaList.class, DoneableKafka.class);
    }
}