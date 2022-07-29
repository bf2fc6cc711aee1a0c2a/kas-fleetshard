package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.Quantity;
import io.javaoperatorsdk.operator.api.reconciler.Constants;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import org.bf2.common.ConditionUtils;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.managers.CapacityManager;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.managers.ObservabilityManager;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.resources.v1alpha1.ClusterCapacity;
import org.bf2.operator.resources.v1alpha1.ClusterCapacityBuilder;
import org.bf2.operator.resources.v1alpha1.ClusterResizeInfo;
import org.bf2.operator.resources.v1alpha1.ClusterResizeInfoBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatusBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Type;
import org.bf2.operator.resources.v1alpha1.NodeCounts;
import org.bf2.operator.resources.v1alpha1.NodeCountsBuilder;
import org.bf2.operator.resources.v1alpha1.ProfileCapacity;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatus;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The controller for {@link ManagedKafkaAgent}.  However there is currently
 * nothing for this to control.  There is just a scheduled job to update the status
 * which will create the singleton resource if needed.
 *
 * An alternative to this approach would be to have the ManagedKafkaControl make status
 * updates directly based upon the changes it sees in the ManagedKafka instances.
 */
@ApplicationScoped
@ControllerConfiguration(finalizerName = Constants.NO_FINALIZER)
public class ManagedKafkaAgentController implements Reconciler<ManagedKafkaAgent> {

    @Inject
    Logger log;

    @Inject
    ManagedKafkaAgentResourceClient agentClient;

    @Inject
    ObservabilityManager observabilityManager;

    @Inject
    StrimziManager strimziManager;

    @Inject
    CapacityManager capacityManager;

    @Inject
    InformerManager informerManager;

    @Timed(value = "controller.update", extraTags = {"resource", "ManagedKafkaAgent"}, description = "Time spent processing createOrUpdate calls")
    @Counted(value = "controller.update", extraTags = {"resource", "ManagedKafkaAgent"}, description = "The number of createOrUpdate calls processed")
    @Override
    public UpdateControl<ManagedKafkaAgent> reconcile(ManagedKafkaAgent resource, Context context) {
        capacityManager.getOrCreateResourceConfigMap(resource);
        this.observabilityManager.createOrUpdateObservabilitySecret(resource.getSpec().getObservability(), resource);
        // since we don't know the prior state, we have to just reconcile everything
        // in case the spec profile information has changed
        informerManager.resyncManagedKafka();
        if (!resource.getMetadata().getFinalizers().isEmpty()) {
            resource.getMetadata().setFinalizers(Collections.emptyList());
            return UpdateControl.updateResource(resource);
        }
        return UpdateControl.noUpdate();
    }

    @Timed(value = "controller.status.update", extraTags = {"resource", "ManagedKafkaAgent"}, description = "Time spent processing status updates")
    @Counted(value = "controller.status.update", extraTags = {"resource", "ManagedKafkaAgent"}, description = "The number of status updates")
    @Scheduled(every = "{agent.status.interval}", concurrentExecution = ConcurrentExecution.SKIP)
    void statusUpdateLoop() {
        ManagedKafkaAgent resource = this.agentClient.getByName(this.agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
        if (resource != null) {
            // check and reinstate if the observability config changed
            this.observabilityManager.createOrUpdateObservabilitySecret(resource.getSpec().getObservability(), resource);
            log.debugf("Tick to update Kafka agent Status in namespace %s", this.agentClient.getNamespace());
            resource.setStatus(buildStatus(resource));
            this.agentClient.replaceStatus(resource);
        }
    }

    private ManagedKafkaAgentStatus buildStatus(ManagedKafkaAgent resource) {
        ManagedKafkaAgentStatus status = resource.getStatus();
        ManagedKafkaCondition readyCondition = null;
        if (status != null) {
            readyCondition = ConditionUtils.findManagedKafkaCondition(status.getConditions(), Type.Ready).orElse(null);
        }

        List<StrimziVersionStatus> strimziVersions = this.strimziManager.getStrimziVersions();
        log.debugf("Strimzi versions %s", strimziVersions);

        // consider the fleetshard operator ready when observability is running and a Strimzi bundle is installed (aka at least one available version)
        Status statusValue = this.observabilityManager.isObservabilityRunning() && !strimziVersions.isEmpty() ?
                ManagedKafkaCondition.Status.True : ManagedKafkaCondition.Status.False;
        if (readyCondition == null) {
            readyCondition = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.Ready, statusValue);
        } else {
            ConditionUtils.updateConditionStatus(readyCondition, statusValue, null, null);
        }

        Map<String, ProfileCapacity> capacity = capacityManager.buildCapacity(resource);

        // dummy capacity information - to be removed
        ClusterCapacity total = new ClusterCapacityBuilder()
                .withConnections(10000)
                .withDataRetentionSize(Quantity.parse("40Gi"))
                .withIngressEgressThroughputPerSec(Quantity.parse("40Gi"))
                .withPartitions(10000)
                .build();

        ClusterCapacity remaining = new ClusterCapacityBuilder()
                .withConnections(10000)
                .withDataRetentionSize(Quantity.parse("40Gi"))
                .withIngressEgressThroughputPerSec(Quantity.parse("40Gi"))
                .withPartitions(10000)
                .build();

        ClusterCapacity delta = new ClusterCapacityBuilder()
                .withConnections(10000)
                .withDataRetentionSize(Quantity.parse("40Gi"))
                .withIngressEgressThroughputPerSec(Quantity.parse("40Gi"))
                .withPartitions(10000)
                .build();

        NodeCounts nodeInfo = new NodeCountsBuilder()
                .withCeiling(0)
                .withCurrent(0)
                .withCurrentWorkLoadMinimum(0)
                .withFloor(0)
                .build();

        ClusterResizeInfo resize = new ClusterResizeInfoBuilder()
                .withDelta(delta)
                .withNodeDelta(3)
                .build();

        return new ManagedKafkaAgentStatusBuilder()
                .withConditions(status == null ? Arrays.asList(readyCondition) : status.getConditions())
                .withTotal(total)
                .withRemaining(remaining)
                .withNodeInfo(nodeInfo)
                .withResizeInfo(resize)
                .withUpdatedTimestamp(ConditionUtils.iso8601Now())
                .withStrimzi(strimziVersions)
                .withCapacity(capacity)
                .build();
    }

}
