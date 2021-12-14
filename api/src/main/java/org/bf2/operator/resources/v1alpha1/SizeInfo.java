package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class SizeInfo {

    private double brokersPerNode;

    public double getBrokersPerNode() {
        return brokersPerNode;
    }

    public void setBrokersPerNode(double brokersPerNode) {
        this.brokersPerNode = brokersPerNode;
    }

}
