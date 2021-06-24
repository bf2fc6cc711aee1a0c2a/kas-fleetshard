package org.bf2.performance.framework;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.performance.OMB;
import org.bf2.performance.OMBDriver;
import org.bf2.performance.OMBWorkload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstraction for collecting and storing test-metadata like cluster info, kafka clusters, omb workload
 * singleton class for getting instance call static getInstance() method
 */
public class TestMetadataCapture {
    static class Clusters {
        @JsonProperty(value = "kafka-env")
        private OpenshiftClusterData kafkaClusterData;
        @JsonProperty(value = "clients-env")
        private OpenshiftClusterData clientClusterData;
    }
    @JsonProperty(value = "openshift-clusters")
    private Clusters clusters = new Clusters();

    @JsonProperty(value = "kafka-clusters")
    private List<KafkaClusterData> kafkas = new ArrayList<>();
    private List<OMBData> workers = new ArrayList<>();
    static TestMetadataCapture instance;

    private TestMetadataCapture() {
    }

    public static synchronized TestMetadataCapture getInstance() {
        if (instance == null) {
            instance = new TestMetadataCapture();
        }
        return instance;
    }

    public void storeKafkaOpenshiftData(KubeClusterResource cluster) throws IOException {
        clusters.kafkaClusterData = new OpenshiftClusterData(cluster);
    }

    public OpenshiftClusterData getKafkaClusterData() {
        return clusters.kafkaClusterData;
    }

    public void storeClientsOpenshiftEnv(KubeClusterResource cluster) throws IOException {
        clusters.clientClusterData = new OpenshiftClusterData(cluster);
    }

    public OpenshiftClusterData getClientClusterData() {
        return clusters.clientClusterData;
    }

    public void storeKafkaCluster(KubeClusterResource cluster, Kafka kafka) throws IOException {
        kafkas.add(new KafkaClusterData(cluster, kafka));
    }

    public void storeOmbData(KubeClusterResource cluster, OMBWorkload workload, OMBDriver driver, OMB omb) throws IOException {
        workers.add(new OMBData(cluster, workload, driver, omb));
    }

    public void cleanKafkaOmbData() {
        kafkas.clear();
        workers.clear();
    }

    public void cleanOpenshiftData() {
        clusters = new Clusters();
    }

    /*public void saveTestMetadata() throws IOException {
        Files.write(new File(logDir, "test-metadata.json").toPath(), instance.toString().getBytes());
    }*/

    @Override
    public String toString() {
        try {
            return Serialization.jsonMapper().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return e.getClass().getName() + " " + e.getMessage();
        }
    }
}
