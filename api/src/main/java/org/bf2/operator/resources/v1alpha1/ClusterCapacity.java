package org.bf2.operator.resources.v1alpha1;

import io.fabric8.kubernetes.api.model.Quantity;
import io.sundr.builder.annotations.Buildable;
import lombok.Getter;
import lombok.Setter;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
@Getter
@Setter
public class ClusterCapacity {
    private Quantity ingressEgressThroughputPerSec;
    private Integer connections;
    private Quantity dataRetentionSize;
    private Integer partitions;

}
