package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class ClusterCapacity {
    private String ingressEgressThroughputPerSec;
    private int connections;
    private String dataRetentionSize;
    private int partitions;

    public String getIngressEgressThroughputPerSec() {
        return ingressEgressThroughputPerSec;
    }

    public void setIngressEgressThroughputPerSec(String ingressEgressThroughputPerSec) {
        this.ingressEgressThroughputPerSec = ingressEgressThroughputPerSec;
    }

    public int getConnections() {
        return connections;
    }

    public void setConnections(int connections) {
        this.connections = connections;
    }

    public String getDataRetentionSize() {
        return dataRetentionSize;
    }

    public void setDataRetentionSize(String dataRetentionSize) {
        this.dataRetentionSize = dataRetentionSize;
    }

    public int getPartitions() {
        return partitions;
    }

    public void setPartitions(int partitions) {
        this.partitions = partitions;
    }
}
