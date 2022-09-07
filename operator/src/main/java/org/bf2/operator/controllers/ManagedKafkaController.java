package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.reconciler.Constants;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
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
import org.bf2.operator.operands.KafkaInstanceConfigurations;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@ControllerConfiguration(finalizerName = Constants.NO_FINALIZER)
public class ManagedKafkaController implements Reconciler<ManagedKafka>, EventSourceInitializer<HasMetadata> {

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

    @Inject
    KafkaInstanceConfigurations configs;

    /**
     * This logic handles events (edge triggers) using level logic.
     * On any modification to the ManagedKafka or it's owned resources,
     * perform a full update to the desired state.
     * This strategy is straight-forward and works well as long as few events are expected.
     */
    @Timed(value = "controller.update", extraTags = {"resource", "ManagedKafka"}, description = "Time spent processing createOrUpdate calls")
    @Counted(value = "controller.update", extraTags = {"resource", "ManagedKafka"}, description = "The number of createOrUpdate calls")
    @Override
    public UpdateControl<ManagedKafka> reconcile(ManagedKafka managedKafka, Context context) {
        if (managedKafka.getId() != null) {
            NDC.push(ManagedKafkaResourceClient.ID_LOG_KEY + "=" + managedKafka.getId());
        }
        try {
            Optional<OperandReadiness> invalid = invalid(managedKafka);
            // if the ManagedKafka resource is "marked" as to be deleted
            if (managedKafka.getSpec().isDeleted()) {
                // check that it's actually not deleted yet, so operands are gone
                if (!kafkaInstance.isDeleted(managedKafka)) {
                    log.infof("Deleting Kafka instance %s/%s %s", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), managedKafka.getMetadata().getResourceVersion());
                    kafkaInstance.delete(managedKafka, context);
                }
            } else if (invalid.isEmpty()) {
                log.infof("Updating Kafka instance %s/%s %s", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), managedKafka.getMetadata().getResourceVersion());
                kafkaInstance.createOrUpdate(managedKafka);
            }
            updateManagedKafkaStatus(managedKafka, invalid);
            if (!managedKafka.getMetadata().getFinalizers().isEmpty()) {
                managedKafka.getMetadata().setFinalizers(Collections.emptyList());
                return UpdateControl.updateResourceAndStatus(managedKafka);
            }
            return UpdateControl.updateStatus(managedKafka);
        } finally {
            if (managedKafka.getId() != null) {
                NDC.pop();
            }
        }
    }

    @Override
    public List<EventSource> prepareEventSources(EventSourceContext<HasMetadata> context) {
        return Arrays.asList(eventSource);
    }

    /**
     * Extract from the current KafkaInstance overall status (Kafka, Canary and AdminServer)
     * a corresponding list of ManagedKafkaCondition(s) to set on the ManagedKafka status
     *
     * @param managedKafka ManagedKafka instance
     * @param invalid
     */
    private void updateManagedKafkaStatus(ManagedKafka managedKafka, Optional<OperandReadiness> invalid) {
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
        OperandReadiness readiness = invalid.filter(r -> !managedKafka.getSpec().isDeleted()).orElse(kafkaInstance.getReadiness(managedKafka));

        ConditionUtils.updateConditionStatus(ready, readiness.getStatus(), readiness.getReason(), readiness.getMessage());

        // routes should always be set on the CR status, even if it's just an empty list
        status.setRoutes(List.of());

        if (configs.getConfig(managedKafka) == null) {
            return; // can't determine anything else
        }

        int replicas = kafkaCluster.getReplicas(managedKafka);

        if (ingressControllerManagerInstance.isResolvable() && kafkaCluster.hasKafkaBeenReady(managedKafka)) {
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
     * Run a validity check on the ManagedKafka custom resource
     *
     * @param managedKafka ManagedKafka custom resource to validate
     * @return readiness indicating an error in the ManagedKafka custom resource, empty Optional otherwise
     */
    private Optional<OperandReadiness> invalid(ManagedKafka managedKafka) {
        if (configs.getConfig(managedKafka) == null) {
            String message = String.format("No valid profile for %s", Serialization.asYaml(managedKafka.getMetadata()));
            log.error(message);
            return Optional.of(new OperandReadiness(Status.False, Reason.Error, message));
        }
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
