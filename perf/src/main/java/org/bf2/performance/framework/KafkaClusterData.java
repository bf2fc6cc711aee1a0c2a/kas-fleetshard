package org.bf2.performance.framework;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.strimzi.api.kafka.model.JvmOptions;
import io.strimzi.api.kafka.model.Kafka;

import java.io.IOException;
import java.util.List;
import java.util.Map;

class KafkaClusterData {
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
        brokers = cluster.kubeClient().client().pods().inNamespace(kafka.getMetadata().getNamespace()).withLabels(Map.of("app.kubernetes.io/name", "kafka", "strimzi.io/cluster", kafka.getMetadata().getName())).list().getItems();
        brokers.forEach(KafkaClusterData::cleanManagedFields);
        zookeepers = cluster.kubeClient().client().pods().inNamespace(kafka.getMetadata().getNamespace()).withLabels(Map.of("app.kubernetes.io/name", "zookeeper", "strimzi.io/cluster", kafka.getMetadata().getName())).list().getItems();
        zookeepers.forEach(KafkaClusterData::cleanManagedFields);
    }

    private static void cleanManagedFields(Pod b) {
        b.getMetadata().setManagedFields(null);
    }

}
