package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEvent;
import io.strimzi.api.kafka.model.Kafka;

import org.bf2.common.ConditionUtils;
import org.bf2.operator.events.ResourceEvent;
import org.bf2.operator.events.ResourceEventSource;
import org.bf2.operator.operands.KafkaInstance;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacityBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.operator.resources.v1alpha1.VersionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Controller
public class ManagedKafkaController implements ResourceController<ManagedKafka> {

    private static final Logger log = LoggerFactory.getLogger(ManagedKafkaController.class);

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
        log.info("Deleting Kafka instance {}/{}", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());
        kafkaInstance.delete(managedKafka, context);
        return DeleteControl.DEFAULT_DELETE;
    }

    public void handleUpdate(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        // if the ManagedKafka resource is "marked" as to be deleted
        if (managedKafka.getSpec().isDeleted()) {
            // check that it's actually not deleted yet, so operands are gone
            if (!kafkaInstance.isDeleted(managedKafka)) {
                log.info("Deleting Kafka instance {}/{}", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());
                kafkaInstance.delete(managedKafka, context);
            }
        } else {
            kafkaInstance.createOrUpdate(managedKafka);
        }
    }

    @Override
    public UpdateControl<ManagedKafka> createOrUpdateResource(ManagedKafka managedKafka, Context<ManagedKafka> context) {

        Optional<CustomResourceEvent> latestManagedKafkaEvent =
                context.getEvents().getLatestOfType(CustomResourceEvent.class);

        if (latestManagedKafkaEvent.isPresent()) {
            handleUpdate(managedKafka, context);
        }

        Optional<ResourceEvent.KafkaEvent> latestKafkaEvent =
                context.getEvents().getLatestOfType(ResourceEvent.KafkaEvent.class);
        if (latestKafkaEvent.isPresent()) {
            Kafka kafka = latestKafkaEvent.get().getResource();
            log.info("Kafka resource {}/{} is changed", kafka.getMetadata().getNamespace(), kafka.getMetadata().getName());
            updateManagedKafkaStatus(managedKafka);
            handleUpdate(managedKafka, context);
            return UpdateControl.updateStatusSubResource(managedKafka);
        }

        Optional<ResourceEvent.DeploymentEvent> latestDeploymentEvent =
                context.getEvents().getLatestOfType(ResourceEvent.DeploymentEvent.class);
        if (latestDeploymentEvent.isPresent()) {
            Deployment deployment = latestDeploymentEvent.get().getResource();
            log.info("Deployment resource {}/{} is changed", deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
            updateManagedKafkaStatus(managedKafka);
            handleUpdate(managedKafka, context);
            return UpdateControl.updateStatusSubResource(managedKafka);
        }

        Optional<ResourceEvent.ServiceEvent> latestServiceEvent =
                context.getEvents().getLatestOfType(ResourceEvent.ServiceEvent.class);
        if (latestServiceEvent.isPresent()) {
            Service service = latestServiceEvent.get().getResource();
            log.info("Service resource {}/{} is changed", service.getMetadata().getNamespace(), service.getMetadata().getName());
            handleUpdate(managedKafka, context);
            return UpdateControl.noUpdate();
        }

        Optional<ResourceEvent.ConfigMapEvent> latestConfigMapEvent =
                context.getEvents().getLatestOfType(ResourceEvent.ConfigMapEvent.class);
        if (latestConfigMapEvent.isPresent()) {
            ConfigMap configMap = latestConfigMapEvent.get().getResource();
            log.info("ConfigMap resource {}/{} is changed", configMap.getMetadata().getNamespace(), configMap.getMetadata().getName());
            handleUpdate(managedKafka, context);
            return UpdateControl.noUpdate();
        }

        Optional<ResourceEvent.SecretEvent> latestSecretEvent =
                context.getEvents().getLatestOfType(ResourceEvent.SecretEvent.class);
        if (latestSecretEvent.isPresent()) {
            Secret secret = latestSecretEvent.get().getResource();
            log.info("Secret resource {}/{} is changed", secret.getMetadata().getNamespace(), secret.getMetadata().getName());
            handleUpdate(managedKafka, context);
            return UpdateControl.noUpdate();
        }

        Optional<ResourceEvent.RouteEvent> latestRouteEvent =
                context.getEvents().getLatestOfType(ResourceEvent.RouteEvent.class);
        if (latestRouteEvent.isPresent()) {
            Route route = latestRouteEvent.get().getResource();
            log.info("Route resource {}/{} is changed", route.getMetadata().getNamespace(), route.getMetadata().getName());
            handleUpdate(managedKafka,context);
            return UpdateControl.noUpdate();
        }

        return UpdateControl.noUpdate();
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
        Optional<ManagedKafkaCondition> optInstalling =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.Type.Installing);
        Optional<ManagedKafkaCondition> optReady =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.Type.Ready);
        Optional<ManagedKafkaCondition> optError =
                ConditionUtils.findManagedKafkaCondition(managedKafkaConditions, ManagedKafkaCondition.Type.Error);

        if (managedKafka.getSpec().isDeleted() && kafkaInstance.isDeleted(managedKafka)) {
            managedKafkaConditions.clear();
            ManagedKafkaCondition deleted = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.Deleted, "True");
            managedKafkaConditions.add(deleted);
        } else if (kafkaInstance.isInstalling(managedKafka)) {
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
            managedKafka.getStatus().setAdminServerURI(kafkaInstance.getAdminServer().Uri(managedKafka));

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
