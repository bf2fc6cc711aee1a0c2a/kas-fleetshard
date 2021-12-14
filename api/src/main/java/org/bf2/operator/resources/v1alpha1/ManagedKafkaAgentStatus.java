package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;

import java.util.List;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ManagedKafkaAgentStatus {

    private List<ManagedKafkaCondition> conditions;

    private ClusterInfo clusterInfo;

    private NodeCounts nodeInfo;

    private SizeInfo sizeInfo;

    private String updatedTimestamp;

    private List<StrimziVersionStatus> strimzi;

    public List<ManagedKafkaCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<ManagedKafkaCondition> conditions) {
        this.conditions = conditions;
    }

    public ClusterInfo getClusterInfo() {
        return clusterInfo;
    }

    public void setClusterInfo(ClusterInfo clusterInfo) {
        this.clusterInfo = clusterInfo;
    }

    public NodeCounts getNodeInfo() {
        return nodeInfo;
    }

    public void setNodeInfo(NodeCounts requiredNodeSizes) {
        this.nodeInfo = requiredNodeSizes;
    }

    public SizeInfo getSizeInfo() {
        return sizeInfo;
    }

    public void setSizeInfo(SizeInfo sizeInfo) {
        this.sizeInfo = sizeInfo;
    }

    public String getUpdatedTimestamp() {
        return updatedTimestamp;
    }

    public void setUpdatedTimestamp(String updatedTimestamp) {
        this.updatedTimestamp = updatedTimestamp;
    }

    public List<StrimziVersionStatus> getStrimzi() {
        return strimzi;
    }

    public void setStrimzi(List<StrimziVersionStatus> strimzi) {
        this.strimzi = strimzi;
    }
}
