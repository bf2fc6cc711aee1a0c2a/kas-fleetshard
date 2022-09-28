package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;
import lombok.Getter;
import lombok.Setter;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
@Getter
@Setter
public class ClusterResizeInfo {
    private int nodeDelta;
    private ClusterCapacity delta;
}
