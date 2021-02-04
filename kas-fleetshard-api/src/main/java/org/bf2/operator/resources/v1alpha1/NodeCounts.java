package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class NodeCounts {
    private int ceiling;
    private int floor;
    private int current;
    private int currentWorkLoadMinimum;

    public int getCeiling() {
        return ceiling;
    }

    public void setCeiling(int ceiling) {
        this.ceiling = ceiling;
    }

    public int getFloor() {
        return floor;
    }

    public void setFloor(int floor) {
        this.floor = floor;
    }

    public int getCurrent() {
        return current;
    }

    public void setCurrent(int current) {
        this.current = current;
    }

    public int getCurrentWorkLoadMinimum() {
        return currentWorkLoadMinimum;
    }

    public void setCurrentWorkLoadMinimum(int currentWorkLoadMinimum) {
        this.currentWorkLoadMinimum = currentWorkLoadMinimum;
    }
}
