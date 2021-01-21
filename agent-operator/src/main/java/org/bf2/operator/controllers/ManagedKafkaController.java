package org.bf2.operator.controllers;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEvent;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.status.Condition;
import org.bf2.operator.KafkaEvent;
import org.bf2.operator.KafkaEventSource;
import org.bf2.operator.KafkaFactory;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaConditionBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Controller
public class ManagedKafkaController implements ResourceController<ManagedKafka> {

    private static final Logger log = LoggerFactory.getLogger(ManagedKafkaController.class);

    @Inject
    private KubernetesClient client;

    private MixedOperation<Kafka, KafkaList, Resource<Kafka>> kafkaClient;

    private KafkaEventSource kafkaEventSource;

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

        Optional<CustomResourceEvent> latestManagedKafkaEvent =
                context.getEvents().getLatestOfType(CustomResourceEvent.class);

        if (latestManagedKafkaEvent.isPresent()) {
            // Kafka resource doesn't exist, has to be created
            if (kafkaClient.withName(managedKafka.getMetadata().getName()).get() == null) {
                Kafka kafka = KafkaFactory.getKafka(managedKafka);
                log.info("Creating Kafka instance {}/{}", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
                try {
                    kafkaClient.create(kafka);
                } catch (Exception e) {
                    log.error("Error creating the Kafka instance", e);
                    return UpdateControl.noUpdate();
                }

                // TODO: factoring out a better mapping of statuses
                ManagedKafkaStatus status = managedKafka.getStatus();
                if (status == null) {
                    ManagedKafkaCondition condition = new ManagedKafkaConditionBuilder()
                            .withReason("KafkaResourceCreated")
                            .withMessage("Kafka resource created")
                            .withType("Ready")
                            .withStatus("True")
                            .withLastTransitionTime(iso8601Now())
                            .build();
                    status = new ManagedKafkaStatusBuilder()
                            .withConditions(condition)
                            .build();
                    managedKafka.setStatus(status);
                }
                return UpdateControl.updateCustomResourceAndStatus(managedKafka);
            // Kafka resource already exists, has to be updated
            } else {
                log.info("Updating Kafka instance {}", managedKafka.getSpec().getKafkaInstance().getVersion());
                // TODO: updating Kafka instance
                return UpdateControl.noUpdate();
            }

        }

        Optional<KafkaEvent> latestKafkaEvent =
                context.getEvents().getLatestOfType(KafkaEvent.class);
        if (latestKafkaEvent.isPresent()) {
            Kafka kafka = latestKafkaEvent.get().getKafka();
            log.info("Kafka resource {}/{} is changed", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
            if (kafka.getStatus() != null) {
                log.info("Kafka conditions = {}", kafka.getStatus().getConditions());
                Condition kafkaCondition = kafka.getStatus().getConditions().get(0);
                // TODO: doing a better mapping, right now it's just reflecting the Kafka resource status
                ManagedKafkaCondition managedKafkaCondition = managedKafka.getStatus().getConditions().get(0);
                managedKafkaCondition.setReason(kafkaCondition.getReason());
                managedKafkaCondition.setMessage(kafkaCondition.getMessage());
                managedKafkaCondition.setType(kafkaCondition.getType());
                managedKafkaCondition.setStatus(kafkaCondition.getStatus());
                managedKafkaCondition.setLastTransitionTime(iso8601Now());
            }
            return UpdateControl.updateCustomResourceAndStatus(managedKafka);
        }

        return UpdateControl.noUpdate();
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        log.info("init");

        kafkaClient = client.customResources(Kafka.class, KafkaList.class);
        kafkaEventSource = KafkaEventSource.createAndRegisterWatch(kafkaClient);
        eventSourceManager.registerEventSource("kafka-event-source", kafkaEventSource);
    }

    /**
     * Returns the current timestamp in ISO 8601 format, for example "2019-07-23T09:08:12.356Z".
     * @return the current timestamp in ISO 8601 format, for example "2019-07-23T09:08:12.356Z".
     */
    public static String iso8601Now() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
    }
}
