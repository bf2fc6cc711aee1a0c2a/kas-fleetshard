package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class ClusterInfo {

    private int remainingBrokers;

    public int getRemainingBrokers() {
        return remainingBrokers;
    }

    public void setRemainingBrokers(int remainingBrokers) {
        this.remainingBrokers = remainingBrokers;
    }

}
