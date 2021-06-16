package io.kafka.performance.data;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.kafka.performance.k8s.KubeClusterResource;
import io.strimzi.api.kafka.model.JvmOptions;
import io.strimzi.api.kafka.model.Kafka;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class KafkaClusterData {
    String name;
    Integer brokerCount;
    Integer zookeeperCount;
    JvmOptions kafkaJvmOps;
    JvmOptions zookeeperJvmOps;
    Map<String, Object> kafkaConfig;
    Map<String, Quantity> kafkaRequestResources;
    Map<String, Quantity> kafkaLimitResources;
    Map<String, Quantity> zookeeperRequestResources;
    Map<String, Quantity> zookeeperLimitResources;
    List<Pod> brokers;
    List<Pod> zookeepers;

    public String getName() {
        return name;
    }

    public Integer getBrokerCount() {
        return brokerCount;
    }

    public Integer getZookeeperCount() {
        return zookeeperCount;
    }

    public JvmOptions getKafkaJvmOps() {
        return kafkaJvmOps;
    }

    public JvmOptions getZookeeperJvmOps() {
        return zookeeperJvmOps;
    }

    public Map<String, Object> getKafkaConfig() {
        return kafkaConfig;
    }

    public Map<String, Quantity> getKafkaRequestResources() {
        return kafkaRequestResources;
    }

    public Map<String, Quantity> getKafkaLimitResources() {
        return kafkaLimitResources;
    }

    public Map<String, Quantity> getZookeeperRequestResources() {
        return zookeeperRequestResources;
    }

    public Map<String, Quantity> getZookeeperLimitResources() {
        return zookeeperLimitResources;
    }

    public List<Pod> getBrokers() {
        return brokers;
    }

    public List<Pod> getZookeepers() {
        return zookeepers;
    }

    public KafkaClusterData(KubeClusterResource cluster, Kafka kafka) throws IOException {
        name = kafka.getMetadata().getName();
        brokerCount = kafka.getSpec().getKafka().getReplicas();
        zookeeperCount = kafka.getSpec().getZookeeper().getReplicas();
        kafkaJvmOps = kafka.getSpec().getKafka().getJvmOptions();
        zookeeperJvmOps = kafka.getSpec().getZookeeper().getJvmOptions();
        kafkaConfig = kafka.getSpec().getKafka().getConfig();
        kafkaRequestResources = kafka.getSpec().getKafka().getResources().getRequests();
        kafkaLimitResources = kafka.getSpec().getKafka().getResources().getLimits();
        zookeeperRequestResources = kafka.getSpec().getZookeeper().getResources().getRequests();
        zookeeperLimitResources = kafka.getSpec().getZookeeper().getResources().getLimits();
        brokers = cluster.kubeClient().namespace(kafka.getMetadata().getNamespace()).listPods(Map.of("app.kubernetes.io/name", "kafka", "strimzi.io/cluster", kafka.getMetadata().getName()));
        brokers.forEach(KafkaClusterData::cleanManagedFields);
        zookeepers = cluster.kubeClient().namespace(kafka.getMetadata().getNamespace()).listPods(Map.of("app.kubernetes.io/name", "zookeeper", "strimzi.io/cluster", kafka.getMetadata().getName()));
        zookeepers.forEach(KafkaClusterData::cleanManagedFields);
    }

    private static void cleanManagedFields(Pod b) {
        b.getMetadata().setManagedFields(null);
    }

}
