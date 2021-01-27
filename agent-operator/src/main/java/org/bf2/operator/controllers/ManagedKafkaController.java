package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEvent;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.events.KafkaEvent;
import org.bf2.operator.events.KafkaEventSource;
import org.bf2.operator.ConditionUtils;
import org.bf2.operator.operands.KafkaInstance;
import org.bf2.operator.resources.v1alpha1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Controller
public class ManagedKafkaController implements ResourceController<ManagedKafka> {

    private static final Logger log = LoggerFactory.getLogger(ManagedKafkaController.class);

    @Inject
    private KubernetesClient kubernetesClient;

    @Inject
    private KafkaResourceClient kafkaResourceClient;

    @Inject
    private KafkaEventSource kafkaEventSource;

    private KafkaInstance kafkaInstance;

    @Override
    public DeleteControl deleteResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        log.info("Deleting Kafka instance {}", managedKafka.getMetadata().getName());

        kafkaResourceClient.delete(managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());

        kubernetesClient.apps()
                .deployments()
                .inNamespace(managedKafka.getMetadata().getNamespace())
                .withName(managedKafka.getMetadata().getName() + "-canary")
                .delete();

        return DeleteControl.DEFAULT_DELETE;
    }

    @Override
    public UpdateControl<ManagedKafka> createOrUpdateResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {

        Optional<CustomResourceEvent> latestManagedKafkaEvent =
                context.getEvents().getLatestOfType(CustomResourceEvent.class);

        if (latestManagedKafkaEvent.isPresent()) {
            // add status if not already available on the ManagedKafka resource
            if (managedKafka.getStatus() == null) {
                managedKafka.setStatus(
                        new ManagedKafkaStatusBuilder()
                                .withConditions(Collections.emptyList())
                                .build());
            }
            // Kafka resource doesn't exist, has to be created
            if (kafkaResourceClient.getByName(managedKafka.getMetadata().getName()) == null) {
                kafkaInstance = KafkaInstance.create(managedKafka);
                Kafka kafka = kafkaInstance.getKafka();
                log.info("Creating Kafka instance {}/{}", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
                try {
                    kafkaResourceClient.create(kafka);

                    Deployment canary = kafkaInstance.getCanary();
                    kubernetesClient.apps().deployments().create(canary);

                    // TODO: applying logic for getting AdminServer and deploying it
                    // Deployment adminServer = kafkaInstance.getAdminServer();
                    // client.apps().deployments().create(adminServer);
                } catch (Exception e) {
                    log.error("Error creating the Kafka instance", e);
                    return UpdateControl.noUpdate();
                }
                return UpdateControl.updateCustomResourceAndStatus(managedKafka);
            // Kafka resource already exists, has to be updated
            } else {
                log.info("Updating Kafka instance {}", managedKafka.getSpec().getVersions().getKafka());
                kafkaInstance.update(managedKafka);
                // TODO: patching the Kafka resource
                // kafkaClient.withName(kafkaInstance.getKafka().getMetadata().getName()).patch(kafkaInstance.getKafka());

                // TODO: patching the Canary deployment
                // client.apps().deployments().withName(kafkaInstance.getCanary().getMetadata().getName()).patch(kafkaInstance.getCanary());

                // TODO: patching the AdminServer deployment
                // client.apps().deployments().withName(kafkaInstance.getAdminServer().getMetadata().getName()).patch(kafkaInstance.getAdminServer());

                return UpdateControl.noUpdate();
            }

        }

        Optional<KafkaEvent> latestKafkaEvent =
                context.getEvents().getLatestOfType(KafkaEvent.class);
        if (latestKafkaEvent.isPresent()) {
            Kafka kafka = latestKafkaEvent.get().getKafka();
            kafkaInstance.setKafka(kafka);

            log.info("Kafka resource {}/{} is changed", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
            if (kafka.getStatus() != null) {
                log.info("Kafka conditions = {}", kafka.getStatus().getConditions());
                toManagedKafkaConditions(managedKafka.getStatus().getConditions());
            }
            return UpdateControl.updateCustomResourceAndStatus(managedKafka);
        }

        return UpdateControl.noUpdate();
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        log.info("init");
        eventSourceManager.registerEventSource("kafka-event-source", kafkaEventSource);
    }

    /**
     * Extract from the current KafkaInstance overall status (Kafka, Canary and AdminServer)
     * a corresponding list of ManagedKafkaCondition(s) to set on the ManagedKafka status
     *
     * @param managedKafkaConditions list of ManagedKafkaCondition(s) to set on the ManagedKafka status
     */
    private void toManagedKafkaConditions(List<ManagedKafkaCondition> managedKafkaConditions) {
        Optional<ManagedKafkaCondition> optInstalling =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.INSTALLING);
        Optional<ManagedKafkaCondition> optReady =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.READY);
        Optional<ManagedKafkaCondition> optError =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.ERROR);

        if (kafkaInstance.isInstalling()) {
            if (optInstalling.isPresent()) {
                ConditionUtils.updateConditionStatus(optInstalling.get(), "True");
            } else {
                ManagedKafkaCondition installing = ConditionUtils.buildCondition(ManagedKafkaCondition.INSTALLING, "True");
                managedKafkaConditions.add(installing);
            }
            // TODO: should we really have even Ready and Error condition type as "False" while installing, so creating them if not exist?
            if (optReady.isPresent()) {
                ConditionUtils.updateConditionStatus(optReady.get(), "False");
            }
            if (optError.isPresent()) {
                ConditionUtils.updateConditionStatus(optError.get(), "False");
            }
        } else if (kafkaInstance.isReady()) {
            if (optInstalling.isPresent()) {
                ConditionUtils.updateConditionStatus(optInstalling.get(), "False");
            }
            if (optReady.isPresent()) {
                ConditionUtils.updateConditionStatus(optReady.get(), "True");
            } else {
                ManagedKafkaCondition ready = ConditionUtils.buildCondition(ManagedKafkaCondition.READY, "True");
                managedKafkaConditions.add(ready);
            }
            if (optError.isPresent()) {
                ConditionUtils.updateConditionStatus(optError.get(), "False");
            }
        } else if (kafkaInstance.isError()) {
            if (optInstalling.isPresent()) {
                ConditionUtils.updateConditionStatus(optInstalling.get(), "False");
            }
            if (optReady.isPresent()) {
                ConditionUtils.updateConditionStatus(optReady.get(), "False");
            }
            if (optError.isPresent()) {
                ConditionUtils.updateConditionStatus(optError.get(), "True");
            } else {
                ManagedKafkaCondition error = ConditionUtils.buildCondition(ManagedKafkaCondition.ERROR, "True");
                managedKafkaConditions.add(error);
            }
        } else {
            throw new IllegalArgumentException("Unknown Kafka instance condition");
        }
    }
}
