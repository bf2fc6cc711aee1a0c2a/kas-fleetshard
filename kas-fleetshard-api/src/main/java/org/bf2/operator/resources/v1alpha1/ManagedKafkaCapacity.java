package org.bf2.operator.resources.v1alpha1;

import io.fabric8.kubernetes.api.model.Quantity;
import io.sundr.builder.annotations.Buildable;

/**
 * Defines the capacity provided by a ManagedKafka instance in terms of throughput, connection, data retention and more
 */
@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class ManagedKafkaCapacity {

    private Quantity ingressEgressThroughputPerSec;
    private Integer totalMaxConnections;
    private Quantity maxDataRetentionSize;
    private Integer maxPartitions;
    private String maxDataRetentionPeriod;

    public Quantity getIngressEgressThroughputPerSec() {
        return ingressEgressThroughputPerSec;
    }

    public void setIngressEgressThroughputPerSec(Quantity ingressEgressThroughputPerSec) {
        this.ingressEgressThroughputPerSec = ingressEgressThroughputPerSec;
    }

    public Integer getTotalMaxConnections() {
        return totalMaxConnections;
    }

    public void setTotalMaxConnections(Integer totalMaxConnections) {
        this.totalMaxConnections = totalMaxConnections;
    }

    public Quantity getMaxDataRetentionSize() {
        return maxDataRetentionSize;
    }

    public void setMaxDataRetentionSize(Quantity maxDataRetentionSize) {
        this.maxDataRetentionSize = maxDataRetentionSize;
    }

    public Integer getMaxPartitions() {
        return maxPartitions;
    }

    public void setMaxPartitions(Integer maxPartitions) {
        this.maxPartitions = maxPartitions;
    }

    public String getMaxDataRetentionPeriod() {
        return maxDataRetentionPeriod;
    }

    public void setMaxDataRetentionPeriod(String maxDataRetentionPeriod) {
        this.maxDataRetentionPeriod = maxDataRetentionPeriod;
    }
}
