package org.bf2.operator.controllers;

import java.util.Arrays;
import java.util.Optional;

import javax.inject.Inject;

import org.bf2.operator.ConditionUtils;
import org.bf2.operator.clients.AgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ClusterCapacity;
import org.bf2.operator.resources.v1alpha1.ClusterCapacityBuilder;
import org.bf2.operator.resources.v1alpha1.ClusterResizeInfo;
import org.bf2.operator.resources.v1alpha1.ClusterResizeInfoBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatusBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaConditionBuilder;
import org.bf2.operator.resources.v1alpha1.NodeCounts;
import org.bf2.operator.resources.v1alpha1.NodeCountsBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import io.javaoperatorsdk.operator.processing.event.internal.CustomResourceEvent;
import io.quarkus.scheduler.Scheduled;

@Controller
public class ManagedKafkaAgentController implements ResourceController<ManagedKafkaAgent> {

    private static final String RESOURCE_NAME = "managed-agent";

    @Inject
    Logger log;

    @Inject
    private AgentResourceClient agentClient;

    @ConfigProperty(name = "KUBERNETES_NAMESPACE")
    private String namespace;

    @Override
    public DeleteControl deleteResource(ManagedKafkaAgent resource, Context<ManagedKafkaAgent> context) {
        log.infof("Deleting Kafka agent instance %s in namespace %s", resource.getMetadata().getName(), this.namespace);

        // nothing to do as resource cleanup, just ack.
        return DeleteControl.DEFAULT_DELETE;
    }

    @Override
    public UpdateControl<ManagedKafkaAgent> createOrUpdateResource(ManagedKafkaAgent resource,
            Context<ManagedKafkaAgent> context) {
        Optional<CustomResourceEvent> latestEvent = context.getEvents().getLatestOfType(CustomResourceEvent.class);
        if (latestEvent.isPresent()) {
            if (resource.getStatus() == null) {
                log.infof("Updating Kafka agent instance %s in namespace %s", resource.getMetadata().getName(),
                        this.namespace);
                // this does not manage any other resources, so nothing to create
                // calculate the node metrics and update
                resource.setStatus(buildStatus(resource));
                return UpdateControl.updateCustomResourceAndStatus(resource);
            } else {
                log.infof("Updating Kafka agent Status %s in namespace %s", resource.getMetadata().getName(),
                        this.namespace);
                resource.setStatus(buildStatus(resource));
                return UpdateControl.updateStatusSubResource(resource);
            }
        }
        return UpdateControl.noUpdate();
    }

    @Override
    public void init(EventSourceManager eventSourceManager) {
        log.info("Managed Kafka Agent started");

    }

    @Scheduled(every = "{agent.calcuateClusterCapacityEvery}")
    void statusUpdateLoop() {
        if (this.agentClient != null) {
            try {
                ManagedKafkaAgent resource = this.agentClient.get(this.namespace, RESOURCE_NAME);
                if (resource != null) {
                    log.debugf("Tick to update Kafka agent Status in namespace %s", this.namespace);
                    resource.setStatus(buildStatus(resource));
                    this.agentClient.updateStatus(resource);
                }
            } catch(RuntimeException e) {
                log.error("failed to invoke process to calculate the capacity of the cluster in kafka agent", e);
            }
        }
    }

    /**
     * TODO: this needs to be replaced with actual metrics
     * @return
     */
    private ManagedKafkaAgentStatus buildStatus(ManagedKafkaAgent resource) {
        ManagedKafkaCondition readyCondition = new ManagedKafkaConditionBuilder()
                .withType(ManagedKafkaCondition.Type.Ready.name())
                .withStatus("True")
                .withLastTransitionTime(ConditionUtils.iso8601Now())
                .build();

        ClusterCapacity total = new ClusterCapacityBuilder()
                .withConnections(10000)
                .withDataRetentionSize("40Gi")
                .withIngressEgressThroughputPerSec("40Gi")
                .withPartitions(10000)
                .build();

        ClusterCapacity remaining = new ClusterCapacityBuilder()
                .withConnections(10000)
                .withDataRetentionSize("40Gi")
                .withIngressEgressThroughputPerSec("40Gi")
                .withPartitions(10000)
                .build();

        ClusterCapacity delta = new ClusterCapacityBuilder()
                .withConnections(10000)
                .withDataRetentionSize("40Gi")
                .withIngressEgressThroughputPerSec("40Gi")
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
                .withTotalCapacity(total)
                .withRemainingCapacity(remaining)
                .withRequiredNodeSizes(nodeInfo)
                .withResizeInfo(resize)
                .build();
    }
}
