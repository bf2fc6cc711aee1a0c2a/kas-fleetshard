package org.bf2.operator.controllers;

import io.fabric8.kubernetes.api.model.Quantity;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import org.bf2.common.AgentResourceClient;
import org.bf2.common.ConditionUtils;
import org.bf2.operator.InformerManager;
import org.bf2.operator.StrimziManager;
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
import org.bf2.operator.secrets.ObservabilityManager;
import org.jboss.logging.Logger;

import javax.inject.Inject;

import java.util.Arrays;
import java.util.List;

/**
 * The controller for {@link ManagedKafkaAgent}.  However there is currently
 * nothing for this to control.  There is just a scheduled job to update the status
 * which will create the singleton resource if needed.
 *
 * An alternative to this approach would be to have the ManagedKafkaControl make status
 * updates directly based upon the changes it sees in the ManagedKafka instances.
 */
@Controller
public class ManagedKafkaAgentController implements ResourceController<ManagedKafkaAgent> {

    @Inject
    Logger log;

    @Inject
    AgentResourceClient agentClient;

    @Inject
    ObservabilityManager observabilityManager;

    @Inject
    InformerManager manager;

    @Inject
    StrimziManager strimziManager;

    @Timed(value = "controller.delete", extraTags = {"resource", "ManagedKafkaAgent"}, description = "Time spent processing delete events")
    @Counted(value = "controller.delete", extraTags = {"resource", "ManagedKafkaAgent"}, description = "The number of delete events") // not expected to be called
    @Override
    public DeleteControl deleteResource(ManagedKafkaAgent resource, Context<ManagedKafkaAgent> context) {
        log.warnf("Deleting Kafka agent instance %s in namespace %s", resource.getMetadata().getName(), this.agentClient.getNamespace());

        // nothing to do as resource cleanup, just ack.
        return DeleteControl.DEFAULT_DELETE;
    }

    @Timed(value = "controller.update", extraTags = {"resource", "ManagedKafkaAgent"}, description = "Time spent processing createOrUpdate calls")
    @Counted(value = "controller.update", extraTags = {"resource", "ManagedKafkaAgent"}, description = "The number of createOrUpdate calls processed")
    @Override
    public UpdateControl<ManagedKafkaAgent> createOrUpdateResource(ManagedKafkaAgent resource,
            Context<ManagedKafkaAgent> context) {
        this.observabilityManager.createOrUpdateObservabilitySecret(resource.getSpec().getObservability(), resource);
        return UpdateControl.noUpdate();
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        log.info("Managed Kafka Agent started");
    }

    @Timed(value = "controller.status.update", extraTags = {"resource", "ManagedKafkaAgent"}, description = "Time spent processing status updates")
    @Counted(value = "controller.status.update", extraTags = {"resource", "ManagedKafkaAgent"}, description = "The number of status updates")
    @Scheduled(every = "{agent.status.interval}", concurrentExecution = ConcurrentExecution.SKIP)
    void statusUpdateLoop() {
        ManagedKafkaAgent resource = this.agentClient.getByName(this.agentClient.getNamespace(), AgentResourceClient.RESOURCE_NAME);
        if (resource != null) {
            // check and reinstate if the observability config changed
            this.observabilityManager.createOrUpdateObservabilitySecret(resource.getSpec().getObservability(), resource);
            log.debugf("Tick to update Kafka agent Status in namespace %s", this.agentClient.getNamespace());
            resource.setStatus(buildStatus(resource));
            this.agentClient.updateStatus(resource);
        }
    }

    /**
     * TODO: this needs to be replaced with actual metrics
     * @return
     */
    private ManagedKafkaAgentStatus buildStatus(ManagedKafkaAgent resource) {
        ManagedKafkaAgentStatus status = resource.getStatus();
        ManagedKafkaCondition readyCondition = null;
        if (status != null) {
            readyCondition = ConditionUtils.findManagedKafkaCondition(status.getConditions(), Type.Ready).orElse(null);
        }
        Status statusValue = this.observabilityManager.isObservabilityRunning()?ManagedKafkaCondition.Status.True:ManagedKafkaCondition.Status.False;
        if (readyCondition == null) {
            readyCondition = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.Ready, statusValue);
        } else {
            ConditionUtils.updateConditionStatus(readyCondition, statusValue, null);
        }

        List<String> strimziVersions = this.strimziManager.getStrimziVersions();

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
                .withConditions(Arrays.asList(readyCondition))
                .withTotal(total)
                .withRemaining(remaining)
                .withNodeInfo(nodeInfo)
                .withResizeInfo(resize)
                .withUpdatedTimestamp(ConditionUtils.iso8601Now())
                .withStrimziVersions(strimziVersions)
                .build();
    }
}
