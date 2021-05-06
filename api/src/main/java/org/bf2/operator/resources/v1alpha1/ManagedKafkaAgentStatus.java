package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

import java.util.List;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class ManagedKafkaAgentStatus {

    private List<ManagedKafkaCondition> conditions;

    private ClusterCapacity total;

    private ClusterCapacity remaining;

    private NodeCounts nodeInfo;

    private ClusterResizeInfo resizeInfo;

    private String updatedTimestamp;

    public List<ManagedKafkaCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<ManagedKafkaCondition> conditions) {
        this.conditions = conditions;
    }

    public ClusterCapacity getTotal() {
        return total;
    }

    public void setTotal(ClusterCapacity totalCapacity) {
        this.total = totalCapacity;
    }

    public ClusterCapacity getRemaining() {
        return remaining;
    }

    public void setRemaining(ClusterCapacity remainingCapacity) {
        this.remaining = remainingCapacity;
    }

    public NodeCounts getNodeInfo() {
        return nodeInfo;
    }

    public void setNodeInfo(NodeCounts requiredNodeSizes) {
        this.nodeInfo = requiredNodeSizes;
    }

    public ClusterResizeInfo getResizeInfo() {
        return resizeInfo;
    }

    public void setResizeInfo(ClusterResizeInfo resizeInfo) {
        this.resizeInfo = resizeInfo;
    }

    public String getUpdatedTimestamp() {
        return updatedTimestamp;
    }

    public void setUpdatedTimestamp(String updatedTimestamp) {
        this.updatedTimestamp = updatedTimestamp;
    }
}
