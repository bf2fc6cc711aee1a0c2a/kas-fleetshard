package org.bf2.performance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.performance.data.KafkaClusterData;
import org.bf2.performance.data.OMBData;
import org.bf2.performance.data.OpenshiftClusterData;
import org.bf2.performance.k8s.KubeClusterResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Abstraction for collecting and storing test-metadata like cluster info, kafka clusters, omb workload
 * singleton class for getting instance call static getInstance() method
 */
public class TestMetadataCapture {
    private OpenshiftClusterData kafkaClusterData;
    private OpenshiftClusterData clientClusterData;
    private JsonNode kafkaClustersConfiguration;
    private JsonNode ombWorkersConfiguration;
    File logDir;
    private final ObjectMapper mapper = new ObjectMapper();
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
        kafkaClusterData = new OpenshiftClusterData(cluster);
    }

    public OpenshiftClusterData getKafkaClusterData() {
        return kafkaClusterData;
    }

    public void storeClientsOpenshiftEnv(KubeClusterResource cluster) throws IOException {
        clientClusterData = new OpenshiftClusterData(cluster);
    }

    public OpenshiftClusterData getClientClusterData() {
        return clientClusterData;
    }

    public void storeKafkaCluster(KubeClusterResource cluster, Kafka kafka) throws IOException {
        ArrayNode kafkas;
        if (kafkaClustersConfiguration == null) {
            kafkaClustersConfiguration = mapper.createObjectNode();
            kafkas = mapper.createArrayNode();
            ((ObjectNode) kafkaClustersConfiguration).put("kafka-clusters", kafkas);
        } else {
            kafkas = kafkaClustersConfiguration.withArray("kafka-clusters");
        }

        kafkas.add(mapper.valueToTree(new KafkaClusterData(cluster, kafka)));
    }

    public void storeOmbData(KubeClusterResource cluster, OMBWorkload workload, OMBDriver driver, OMB omb) throws IOException {
        ArrayNode workers;
        if (ombWorkersConfiguration == null) {
            ombWorkersConfiguration = mapper.createObjectNode();
            workers = mapper.createArrayNode();
            ((ObjectNode) ombWorkersConfiguration).put("workers", workers);
        } else {
            workers = ombWorkersConfiguration.withArray("workers");
        }
        workers.add(mapper.valueToTree(new OMBData(cluster, workload, driver, omb)));
    }

    public void cleanKafkaOmbData() {
        kafkaClustersConfiguration = null;
        ombWorkersConfiguration = null;
    }

    public void cleanOpenshiftData() {
        kafkaClusterData = null;
        clientClusterData = null;
    }

    public void setLogDir(File file) {
        this.logDir = file;
    }

    public void saveTestMetadata() throws IOException {
        Files.write(new File(logDir, "test-metadata.json").toPath(), instance.toString().getBytes());
    }

    private JsonNode getOpenshiftEnv() throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode clusters = mapper.createObjectNode();
        clusters.put("kafka-env", mapper.valueToTree(kafkaClusterData));
        clusters.put("clients-env", mapper.valueToTree(clientClusterData));
        root.put("clusters", clusters);
        return root;
    }

    @Override
    public String toString() {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("openshift-clusters", getOpenshiftEnv().get("clusters"));
            root.put("kafka-clusters", kafkaClustersConfiguration == null ? null : kafkaClustersConfiguration.get("kafka-clusters"));
            root.put("workers", ombWorkersConfiguration == null ? null : ombWorkersConfiguration.get("workers"));
            return root.toPrettyString();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return "";
    }
}
