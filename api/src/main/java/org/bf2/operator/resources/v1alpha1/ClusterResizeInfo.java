package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class ClusterResizeInfo {
    private int nodeDelta;
    private ClusterCapacityDelta delta;

    public int getNodeDelta() {
        return nodeDelta;
    }

    public void setNodeDelta(int nodeDelta) {
        this.nodeDelta = nodeDelta;
    }

    public ClusterCapacityDelta getDelta() {
        return delta;
    }

    public void setDelta(ClusterCapacityDelta delta) {
        this.delta = delta;
    }
}
