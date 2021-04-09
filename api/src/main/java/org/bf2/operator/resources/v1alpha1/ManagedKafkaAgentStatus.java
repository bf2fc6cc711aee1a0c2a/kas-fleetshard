package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.sundr.builder.annotations.Buildable;

import java.util.List;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class ManagedKafkaAgentStatus {

    private List<ManagedKafkaCondition> conditions;

    @JsonProperty(value = "total")
    private ClusterCapacity totalCapacity;

    @JsonProperty(value = "remaining")
    private ClusterCapacity remainingCapacity;

    @JsonProperty(value = "nodeInfo")
    private NodeCounts requiredNodeSizes;

    @JsonProperty(value = "resizeInfo")
    private ClusterResizeInfo resizeInfo;

    public List<ManagedKafkaCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<ManagedKafkaCondition> conditions) {
        this.conditions = conditions;
    }

    public ClusterCapacity getTotalCapacity() {
        return totalCapacity;
    }

    public void setTotalCapacity(ClusterCapacity totalCapacity) {
        this.totalCapacity = totalCapacity;
    }

    public ClusterCapacity getRemainingCapacity() {
        return remainingCapacity;
    }

    public void setRemainingCapacity(ClusterCapacity remainingCapacity) {
        this.remainingCapacity = remainingCapacity;
    }

    public NodeCounts getRequiredNodeSizes() {
        return requiredNodeSizes;
    }

    public void setRequiredNodeSizes(NodeCounts requiredNodeSizes) {
        this.requiredNodeSizes = requiredNodeSizes;
    }

    public ClusterResizeInfo getResizeInfo() {
        return resizeInfo;
    }

    public void setResizeInfo(ClusterResizeInfo resizeInfo) {
        this.resizeInfo = resizeInfo;
    }
}
