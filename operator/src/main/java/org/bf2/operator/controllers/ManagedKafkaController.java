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
import org.bf2.operator.managers.IngressControllerManager;
import org.bf2.operator.managers.KafkaManager;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.operands.KafkaInstance;
import org.bf2.operator.operands.OperandReadiness;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacityBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaRoute;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatus;
import org.bf2.operator.resources.v1alpha1.VersionsBuilder;
import org.jboss.logging.Logger;
import org.jboss.logging.NDC;

import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Controller
public class ManagedKafkaController implements ResourceController<ManagedKafka> {

    // 1 for bootstrap URL + 1 for Admin API server
    private static final int NUM_NON_BROKER_ROUTES = 2;

    @Inject
    Logger log;

    @Inject
    ResourceEventSource eventSource;

    @Inject
    KafkaInstance kafkaInstance;

    @Inject
    Instance<IngressControllerManager> ingressControllerManagerInstance;

    @Inject
    StrimziManager strimziManager;

    @Inject
    KafkaManager kafkaManager;

    @Inject
    AbstractKafkaCluster kafkaCluster;

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
            if (this.isValid(managedKafka)) {
                log.infof("Updating Kafka instance %s/%s %s - modified %s", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), managedKafka.getMetadata().getResourceVersion(), context.getEvents().getList());
                kafkaInstance.createOrUpdate(managedKafka);
            }
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
            handleUpdate(managedKafka, context);
            updateManagedKafkaStatus(managedKafka);
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
     */
    private void updateManagedKafkaStatus(ManagedKafka managedKafka) {
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
            status.setConditions(managedKafkaConditions);
        }
        Optional<ManagedKafkaCondition> optReady =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.Type.Ready);

        ManagedKafkaCondition ready = null;

        if (optReady.isPresent()) {
            ready = optReady.get();
        } else {
            ready = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.Ready, Status.Unknown);
            managedKafkaConditions.add(ready);
        }

        // a not valid ManagedKafka skips the handling of it, so the status will report an error condition
        OperandReadiness readiness = this.validity(managedKafka).orElse(kafkaInstance.getReadiness(managedKafka));

        ConditionUtils.updateConditionStatus(ready, readiness.getStatus(), readiness.getReason(), readiness.getMessage());

        // routes should always be set on the CR status, even if it's just an empty list
        status.setRoutes(List.of());

        int replicas = kafkaCluster.getReplicas(managedKafka);

        if (ingressControllerManagerInstance.isResolvable()) {
            IngressControllerManager ingressControllerManager = ingressControllerManagerInstance.get();
            List<ManagedKafkaRoute> routes = ingressControllerManager.getManagedKafkaRoutesFor(managedKafka);

            // expect route for each broker + 1 for bootstrap URL + 1 for Admin API server
            int expectedNumRoutes = replicas + NUM_NON_BROKER_ROUTES;
            if (routes.size() >= expectedNumRoutes && routes.stream().noneMatch(r -> "".equals(r.getRouter()))) {
                status.setRoutes(routes);
            }
        }

        if (Status.True.equals(readiness.getStatus())) {
            status.setCapacity(new ManagedKafkaCapacityBuilder(managedKafka.getSpec().getCapacity())
                    .withMaxDataRetentionSize(kafkaInstance.getKafkaCluster().calculateRetentionSize(managedKafka))
                    .build());
            // the versions in the status are updated incrementally copying the spec only when each stage ends
            VersionsBuilder versionsBuilder = status.getVersions() != null ?
                    new VersionsBuilder(status.getVersions()) : new VersionsBuilder(managedKafka.getSpec().getVersions());
            if (!Reason.StrimziUpdating.equals(readiness.getReason()) && !this.strimziManager.hasStrimziChanged(managedKafka)) {
                versionsBuilder.withStrimzi(managedKafka.getSpec().getVersions().getStrimzi());
            }
            if (!Reason.KafkaUpdating.equals(readiness.getReason()) && !this.kafkaManager.hasKafkaVersionChanged(managedKafka)) {
                versionsBuilder.withKafka(managedKafka.getSpec().getVersions().getKafka());
            }
            if (!Reason.KafkaIbpUpdating.equals(readiness.getReason()) && !this.kafkaManager.hasKafkaIbpVersionChanged(managedKafka)) {
                String kafkaIbp = managedKafka.getSpec().getVersions().getKafkaIbp() != null ?
                        managedKafka.getSpec().getVersions().getKafkaIbp() :
                        AbstractKafkaCluster.getKafkaIbpVersion(managedKafka.getSpec().getVersions().getKafka());
                versionsBuilder.withKafkaIbp(kafkaIbp);
            }
            status.setVersions(versionsBuilder.build());
            status.setAdminServerURI(kafkaInstance.getAdminServer().uri(managedKafka));
            status.setServiceAccounts(managedKafka.getSpec().getServiceAccounts());
        }
    }

    /**
     * Just a wrapper around the ManagedKafka validity check to return a boolean
     *
     * @param managedKafka ManagedKafka custom resource to validate
     * @return if its specification is valid or not
     */
    private boolean isValid(ManagedKafka managedKafka) {
        return validity(managedKafka).isEmpty();
    }

    /**
     * Run a validity check on the ManagedKafka custom resource
     *
     * @param managedKafka ManagedKafka custom resource to validate
     * @return readiness indicating an error in the ManagedKafka custom resource, empty Optional otherwise
     */
    private Optional<OperandReadiness> validity(ManagedKafka managedKafka) {
        String message = null;
        StrimziVersionStatus strimziVersion = this.strimziManager.getStrimziVersion(managedKafka.getSpec().getVersions().getStrimzi());
        if (strimziVersion == null) {
            message = String.format("The requested Strimzi version %s is not supported", managedKafka.getSpec().getVersions().getStrimzi());
        } else {
            if (!strimziVersion.getKafkaVersions().contains(managedKafka.getSpec().getVersions().getKafka())) {
                message = String.format("The requested Kafka version %s is not supported by the Strimzi version %s",
                        managedKafka.getSpec().getVersions().getKafka(), strimziVersion.getVersion());
            } else if (managedKafka.getSpec().getVersions().getKafkaIbp() != null &&
                    !strimziVersion.getKafkaIbpVersions().contains(managedKafka.getSpec().getVersions().getKafkaIbp())) {
                message = String.format("The requested Kafka inter broker protocol version %s is not supported by the Strimzi version %s",
                        managedKafka.getSpec().getVersions().getKafkaIbp(), strimziVersion.getVersion());
            }
        }
        if (message != null) {
            log.error(message);
            return Optional.of(new OperandReadiness(Status.False, Reason.Error, message));
        }
        return Optional.empty();
    }
}
