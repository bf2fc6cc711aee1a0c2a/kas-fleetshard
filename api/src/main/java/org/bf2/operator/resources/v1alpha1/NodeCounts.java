package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;
import lombok.Getter;
import lombok.Setter;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
@Getter
@Setter
public class NodeCounts {
    private int ceiling;
    private int floor;
    private int current;
    private int currentWorkLoadMinimum;

}
