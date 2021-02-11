package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEvent;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.events.DeploymentEvent;
import org.bf2.operator.events.DeploymentEventSource;
import org.bf2.operator.events.KafkaEvent;
import org.bf2.operator.events.KafkaEventSource;
import org.bf2.operator.ConditionUtils;
import org.bf2.operator.operands.AdminServer;
import org.bf2.operator.operands.Canary;
import org.bf2.operator.operands.KafkaInstance;
import org.bf2.operator.resources.v1alpha1.*;
import org.jboss.logging.Logger;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Controller
public class ManagedKafkaController implements ResourceController<ManagedKafka> {

    @Inject
    Logger log;

    @Inject
    KafkaEventSource kafkaEventSource;

    @Inject
    DeploymentEventSource deploymentEventSource;

    @Inject
    KafkaInstance kafkaInstance;

    @Override
    public DeleteControl deleteResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        log.infof("Deleting Kafka instance %s", managedKafka.getMetadata().getName());
        kafkaInstance.delete(managedKafka, context);
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
            try {
                kafkaInstance.createOrUpdate(managedKafka);
            } catch (Exception ex) {
                log.errorf("Error reconciling %s", managedKafka.getMetadata().getName(), ex);
                return UpdateControl.noUpdate();
            }
            return UpdateControl.updateCustomResourceAndStatus(managedKafka);
        }

        Optional<KafkaEvent> latestKafkaEvent =
                context.getEvents().getLatestOfType(KafkaEvent.class);
        if (latestKafkaEvent.isPresent()) {
            Kafka kafka = latestKafkaEvent.get().getKafka();
            log.infof("Kafka resource %s/%s is changed", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
            if (kafka.getStatus() != null) {
                log.infof("Kafka conditions = %s", kafka.getStatus().getConditions());
                updateManagedKafkaStatus(managedKafka);
            }
            return UpdateControl.updateCustomResourceAndStatus(managedKafka);
        }

        Optional<DeploymentEvent> latestDeploymentEvent =
                context.getEvents().getLatestOfType(DeploymentEvent.class);
        if (latestDeploymentEvent.isPresent()) {
            Deployment deployment = latestDeploymentEvent.get().getDeployment();
            log.infof("Deployment resource %s/%s is changed", deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());

            // check if the Deployment is related to Canary or Admin Server
            // NOTE: the informer already filter only these Deployments, just checking for safety
            if (deployment.getMetadata().getName().equals(Canary.canaryName(managedKafka)) ||
                deployment.getMetadata().getName().equals(AdminServer.adminServerName(managedKafka))) {
                if (deployment.getStatus() != null) {
                    log.infof("Deployment conditions = %s", deployment.getStatus().getConditions());
                    updateManagedKafkaStatus(managedKafka);
                }
            }
            return UpdateControl.updateCustomResourceAndStatus(managedKafka);
        }

        return UpdateControl.noUpdate();
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        log.info("init");
        eventSourceManager.registerEventSource("kafka-event-source", kafkaEventSource);
        eventSourceManager.registerEventSource("deployment-event-source", deploymentEventSource);
    }

    /**
     * Extract from the current KafkaInstance overall status (Kafka, Canary and AdminServer)
     * a corresponding list of ManagedKafkaCondition(s) to set on the ManagedKafka status
     *
     * @param managedKafka ManagedKafka instance
     */
    private void updateManagedKafkaStatus(ManagedKafka managedKafka) {
        List<ManagedKafkaCondition> managedKafkaConditions = managedKafka.getStatus().getConditions();
        Optional<ManagedKafkaCondition> optInstalling =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.Type.Installing);
        Optional<ManagedKafkaCondition> optReady =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.Type.Ready);
        Optional<ManagedKafkaCondition> optError =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.Type.Error);

        if (kafkaInstance.isInstalling(managedKafka)) {
            if (optInstalling.isPresent()) {
                ConditionUtils.updateConditionStatus(optInstalling.get(), "True");
            } else {
                ManagedKafkaCondition installing = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.Installing, "True");
                managedKafkaConditions.add(installing);
            }
            // TODO: should we really have even Ready and Error condition type as "False" while installing, so creating them if not exist?
            if (optReady.isPresent()) {
                ConditionUtils.updateConditionStatus(optReady.get(), "False");
            }
            if (optError.isPresent()) {
                ConditionUtils.updateConditionStatus(optError.get(), "False");
            }
        } else if (kafkaInstance.isReady(managedKafka)) {
            if (optInstalling.isPresent()) {
                ConditionUtils.updateConditionStatus(optInstalling.get(), "False");
            }
            if (optReady.isPresent()) {
                ConditionUtils.updateConditionStatus(optReady.get(), "True");
            } else {
                ManagedKafkaCondition ready = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.Ready, "True");
                managedKafkaConditions.add(ready);
            }
            if (optError.isPresent()) {
                ConditionUtils.updateConditionStatus(optError.get(), "False");
            }

            // TODO: just reflecting for now what was defined in the spec
            managedKafka.getStatus().setCapacity(new ManagedKafkaCapacityBuilder(managedKafka.getSpec().getCapacity()).build());
            managedKafka.getStatus().setVersions(new VersionsBuilder(managedKafka.getSpec().getVersions()).build());

        } else if (kafkaInstance.isError(managedKafka)) {
            if (optInstalling.isPresent()) {
                ConditionUtils.updateConditionStatus(optInstalling.get(), "False");
            }
            if (optReady.isPresent()) {
                ConditionUtils.updateConditionStatus(optReady.get(), "False");
            }
            if (optError.isPresent()) {
                ConditionUtils.updateConditionStatus(optError.get(), "True");
            } else {
                ManagedKafkaCondition error = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.Error, "True");
                managedKafkaConditions.add(error);
            }
        }
    }
}
