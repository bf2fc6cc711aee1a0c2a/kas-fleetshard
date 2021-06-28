package org.bf2.performance.framework;

import io.fabric8.kubernetes.api.model.Node;

class OpenshiftNodeData {
    String name;
    String role;
    String flavor;
    String region;
    String containerRuntimeVersion;
    String cpu;
    String memory;

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public String getFlavor() {
        return flavor;
    }

    public String getRegion() {
        return region;
    }

    public String getContainerRuntimeVersion() {
        return containerRuntimeVersion;
    }

    public String getCpu() {
        return cpu;
    }

    public String getMemory() {
        return memory;
    }

    OpenshiftNodeData(Node node) {
        this.name = node.getMetadata().getName();
        this.role = node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/master") ? "master" :
                node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/infra") ? "infra" :
                        node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/worker") ? "worker" : "";
        this.flavor = node.getMetadata().getLabels().get("node.kubernetes.io/instance-type");
        this.region = node.getMetadata().getLabels().get("topology.kubernetes.io/region");
        this.containerRuntimeVersion = node.getStatus().getNodeInfo().getContainerRuntimeVersion();
        this.cpu = node.getStatus().getCapacity().get("cpu").toString();
        this.memory = node.getStatus().getCapacity().get("memory").toString();
    }
}
