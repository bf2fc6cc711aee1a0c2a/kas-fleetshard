package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class NodeCounts {
    private int current;
    private int workLoadMinimum;

    public int getCurrent() {
        return current;
    }

    public void setCurrent(int current) {
        this.current = current;
    }

    public int getWorkLoadMinimum() {
        return workLoadMinimum;
    }

    public void setWorkLoadMinimum(int workLoadMinimum) {
        this.workLoadMinimum = workLoadMinimum;
    }

}
