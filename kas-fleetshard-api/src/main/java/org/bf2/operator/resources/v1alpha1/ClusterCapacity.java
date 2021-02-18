package org.bf2.operator.resources.v1alpha1;

import io.fabric8.kubernetes.api.model.Quantity;
import io.sundr.builder.annotations.Buildable;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class ClusterCapacity {
    private Quantity ingressEgressThroughputPerSec;
    private Integer connections;
    private Quantity dataRetentionSize;
    private Integer partitions;

    public Quantity getIngressEgressThroughputPerSec() {
        return ingressEgressThroughputPerSec;
    }

    public void setIngressEgressThroughputPerSec(Quantity ingressEgressThroughputPerSec) {
        this.ingressEgressThroughputPerSec = ingressEgressThroughputPerSec;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    public Quantity getDataRetentionSize() {
        return dataRetentionSize;
    }

    public void setDataRetentionSize(Quantity dataRetentionSize) {
        this.dataRetentionSize = dataRetentionSize;
    }

    public Integer getPartitions() {
        return partitions;
    }

    public void setPartitions(Integer partitions) {
        this.partitions = partitions;
    }
}
