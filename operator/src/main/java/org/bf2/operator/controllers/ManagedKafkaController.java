package org.bf2.operator.controllers;

import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import org.bf2.common.ConditionUtils;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.operator.events.ResourceEventSource;
import org.bf2.operator.operands.KafkaInstance;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacityBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.operator.resources.v1alpha1.VersionsBuilder;
import org.jboss.logging.Logger;
import org.jboss.logging.NDC;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class ManagedKafkaController implements ResourceController<ManagedKafka> {

    @Inject
    Logger log;

    @Inject
    ResourceEventSource eventSource;

    @Inject
    KafkaInstance kafkaInstance;

    @Override
    @Timed(value = "controller.delete", extraTags = {"resource", "ManagedKafka"}, description = "Time spent processing delete events")
    @Counted(value = "controller.delete", extraTags = {"resource", "ManagedKafka"}, description = "The number of delete events")
    public DeleteControl deleteResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        log.infof("Kafka instance %s/%s fully deleted", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());
        return DeleteControl.DEFAULT_DELETE;
    }

    public void handleUpdate(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        // if the ManagedKafka resource is "marked" as to be deleted
        if (managedKafka.getSpec().isDeleted()) {
            // check that it's actually not deleted yet, so operands are gone
            if (!kafkaInstance.isDeleted(managedKafka)) {
                log.infof("Deleting Kafka instance %s/%s %s - modified %s", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), managedKafka.getMetadata().getResourceVersion(), context.getEvents().getList());
                kafkaInstance.delete(managedKafka, context);
            }
        } else {
            log.infof("Updating Kafka instance %s/%s %s - modified %s", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), managedKafka.getMetadata().getResourceVersion(), context.getEvents().getList());
            kafkaInstance.createOrUpdate(managedKafka);
        }
    }

    /**
     * This logic handles events (edge triggers) using level logic.
     * On any modification to the ManagedKafka or it's owned resources,
     * perform a full update to the desired state.
     * This strategy is straight-forward and works well as long as few events are expected.
     */
    @Override
    @Timed(value = "controller.update", extraTags = {"resource", "ManagedKafka"}, description = "Time spent processing createOrUpdate calls")
    @Counted(value = "controller.update", extraTags = {"resource", "ManagedKafka"}, description = "The number of createOrUpdate calls")
    public UpdateControl<ManagedKafka> createOrUpdateResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        if (managedKafka.getId() != null) {
            NDC.push(ManagedKafkaResourceClient.ID_LOG_KEY + "=" + managedKafka.getId());
        }
        try {
            List<ManagedKafkaCondition> warningConditions = kafkaInstance.validate(managedKafka);
            handleUpdate(managedKafka, context);
            updateManagedKafkaStatus(managedKafka, warningConditions);
            return UpdateControl.updateStatusSubResource(managedKafka);
        } finally {
            if (managedKafka.getId() != null) {
                NDC.pop();
            }
        }
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        log.info("init");
        eventSourceManager.registerEventSource("event-source", eventSource);
    }

    /**
     * Extract from the current KafkaInstance overall status (Kafka, Canary and AdminServer)
     * a corresponding list of ManagedKafkaCondition(s) to set on the ManagedKafka status
     *
     * @param managedKafka ManagedKafka instance
     * @param warningConditions warning related conditions to add to the status
     */
    private void updateManagedKafkaStatus(ManagedKafka managedKafka, List<ManagedKafkaCondition> warningConditions) {
        // add status if not already available on the ManagedKafka resource
        ManagedKafkaStatus status = Objects.requireNonNullElse(managedKafka.getStatus(),
                new ManagedKafkaStatusBuilder()
                .build());
        status.setUpdatedTimestamp(ConditionUtils.iso8601Now());
        managedKafka.setStatus(status);

        // add conditions if not already available
        List<ManagedKafkaCondition> managedKafkaConditions = managedKafka.getStatus().getConditions();
        if (managedKafkaConditions == null) {
            managedKafkaConditions = new ArrayList<>();
        } else {
            // remove all current warning conditions to add the ones from the latest validation
            managedKafkaConditions = managedKafkaConditions.stream()
                    .filter(mk -> mk.getType() != null && !mk.getType().equals(ManagedKafkaCondition.Type.Warning.name()))
                    .collect(Collectors.toList());
        }
        managedKafkaConditions.addAll(warningConditions);
        status.setConditions(managedKafkaConditions);

        Optional<ManagedKafkaCondition> optReady =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.Type.Ready);

        ManagedKafkaCondition ready = null;

        if (optReady.isPresent()) {
            ready = optReady.get();
        } else {
            ready = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.Ready, Status.Unknown);
            managedKafkaConditions.add(ready);
        }

        if (managedKafka.getSpec().isDeleted()) {
            ConditionUtils.updateConditionStatus(ready,
                    kafkaInstance.isDeleted(managedKafka) ? Status.False : Status.Unknown, Reason.Deleted);
        } else if (kafkaInstance.isInstalling(managedKafka)) {
            ConditionUtils.updateConditionStatus(ready, Status.False, Reason.Installing);
        } else if (kafkaInstance.isReady(managedKafka)) {
            ConditionUtils.updateConditionStatus(ready, Status.True, null);

            managedKafka.getStatus().setVersions(
                    new VersionsBuilder()
                            .withKafka(kafkaInstance.getKafkaCluster().currentKafkaVersion(managedKafka))
                            .withStrimzi(kafkaInstance.getKafkaCluster().currentStrimziVersion(managedKafka))
                            .build());
            managedKafka.getStatus().setAdminServerURI(kafkaInstance.getAdminServer().uri(managedKafka));
            // TODO: for capacity just reflecting for now what was defined in the spec
            managedKafka.getStatus().setCapacity(new ManagedKafkaCapacityBuilder(managedKafka.getSpec().getCapacity()).build());

        } else if (kafkaInstance.isError(managedKafka)) {
            ConditionUtils.updateConditionStatus(ready, Status.False, Reason.Error);
        } else if (kafkaInstance.isStrimziUpdating(managedKafka)) {
            ConditionUtils.updateConditionStatus(ready, Status.True, Reason.StrimziUpdating);
        } else {
            ConditionUtils.updateConditionStatus(ready, Status.Unknown, null);
        }
    }
}
