package org.bf2.performance.data;

import io.fabric8.kubernetes.api.model.Node;
import org.bf2.performance.k8s.KubeClusterResource;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class OpenshiftClusterData {
    Integer countOfNodes;
    Integer countOfWorkers;
    String workerInstanceType;
    String infraInstanceType;
    List<OpenshiftNodeData> nodes;

    public Integer getCountOfNodes() {
        return countOfNodes;
    }

    public Integer getCountOfWorkers() {
        return countOfWorkers;
    }

    public String getWorkerInstanceType() {
        return workerInstanceType;
    }

    public String getInfraInstanceType() {
        return infraInstanceType;
    }

    public List<OpenshiftNodeData> getNodes() {
        return nodes;
    }

    public OpenshiftClusterData(KubeClusterResource cluster) throws IOException {
        List<Node> nodes = cluster.kubeClient().client().nodes().list().getItems();
        this.countOfNodes = nodes.size();
        this.countOfWorkers = (int) nodes.stream().filter(OpenshiftClusterData::isWorker).count();
        this.workerInstanceType = nodes.stream().filter(OpenshiftClusterData::isWorker).map(OpenshiftClusterData::instanceType).filter(Objects::nonNull).findFirst().orElse(null);
        this.infraInstanceType = nodes.stream().filter(OpenshiftClusterData::isInfra).map(OpenshiftClusterData::instanceType).filter(Objects::nonNull).findFirst().orElse(null);
        this.nodes = new LinkedList<>();
        for (Node node : nodes) {
            this.nodes.add(new OpenshiftNodeData(node));
        }
    }

    private static boolean isWorker(Node node) {
        return node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/worker") &&
                !node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/infra");
    }

    private static boolean isInfra(Node node) {
        return node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/infra");
    }

    private static String instanceType(Node n) {
        return n.getMetadata().getLabels().get("beta.kubernetes.io/instance-type");
    }
}
