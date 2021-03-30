package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.Event;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEvent;

import org.bf2.common.ConditionUtils;
import org.bf2.operator.events.ResourceEvent;
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

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Controller
public class ManagedKafkaController implements ResourceController<ManagedKafka> {

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ResourceEventSource.KafkaEventSource kafkaEventSource;

    @Inject
    ResourceEventSource.DeploymentEventSource deploymentEventSource;

    @Inject
    ResourceEventSource.ServiceEventSource serviceEventSource;

    @Inject
    ResourceEventSource.ConfigMapEventSource configMapEventSource;

    @Inject
    ResourceEventSource.SecretEventSource secretEventSource;

    @Inject
    ResourceEventSource.RouteEventSource routeEventSource;

    @Inject
    KafkaInstance kafkaInstance;

    @Override
    public DeleteControl deleteResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        log.infof("Deleting Kafka instance %s/%s", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());
        kafkaInstance.delete(managedKafka, context);
        return DeleteControl.DEFAULT_DELETE;
    }

    public void handleUpdate(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        // if the ManagedKafka resource is "marked" as to be deleted
        if (managedKafka.getSpec().isDeleted()) {
            // check that it's actually not deleted yet, so operands are gone
            if (!kafkaInstance.isDeleted(managedKafka)) {
                log.infof("Deleting Kafka instance %s/%s", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());
                kafkaInstance.delete(managedKafka, context);
            }
        } else {
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
    public UpdateControl<ManagedKafka> createOrUpdateResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        if (log.isDebugEnabled()) {
            for (Event event : context.getEvents().getList()) {
                if (event instanceof ResourceEvent) {
                    ResourceEvent<?> resourceEvent = (ResourceEvent<?>)event;
                    HasMetadata resource = resourceEvent.getResource();
                    log.debugf("%s resource %s/%s is changed", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());
                } else if (event instanceof CustomResourceEvent) {
                    log.debugf("ManagedKafka resource %s/%s is changed", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());
                }
            }
        }
        handleUpdate(managedKafka, context);
        updateManagedKafkaStatus(managedKafka);
        return UpdateControl.updateStatusSubResource(managedKafka);
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        log.info("init");
        eventSourceManager.registerEventSource("kafka-event-source", kafkaEventSource);
        eventSourceManager.registerEventSource("deployment-event-source", deploymentEventSource);
        eventSourceManager.registerEventSource("service-event-source", serviceEventSource);
        eventSourceManager.registerEventSource("configmap-event-source", configMapEventSource);
        eventSourceManager.registerEventSource("secret-event-source", secretEventSource);
        if (kubernetesClient.isAdaptable(OpenShiftClient.class)) {
            eventSourceManager.registerEventSource("route-event-source", routeEventSource);
        }
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

        if (managedKafka.getSpec().isDeleted()) {
            ConditionUtils.updateConditionStatus(ready,
                    kafkaInstance.isDeleted(managedKafka) ? Status.False : Status.Unknown, Reason.Deleted);
        } else if (kafkaInstance.isInstalling(managedKafka)) {
            ConditionUtils.updateConditionStatus(ready, Status.False, Reason.Installing);
        } else if (kafkaInstance.isReady(managedKafka)) {
            ConditionUtils.updateConditionStatus(ready, Status.True, null);

            // TODO: just reflecting for now what was defined in the spec
            managedKafka.getStatus().setCapacity(new ManagedKafkaCapacityBuilder(managedKafka.getSpec().getCapacity()).build());
            managedKafka.getStatus().setVersions(new VersionsBuilder(managedKafka.getSpec().getVersions()).build());
            managedKafka.getStatus().setAdminServerURI(kafkaInstance.getAdminServer().Uri(managedKafka));

        } else if (kafkaInstance.isError(managedKafka)) {
            ConditionUtils.updateConditionStatus(ready, Status.False, Reason.Error);
        } else {
            ConditionUtils.updateConditionStatus(ready, Status.Unknown, null);
        }
    }
}
