package org.bf2.operator.operands;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.quarkus.arc.config.ConfigProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ConfigProperties(prefix = "kafka")
public class KafkaInstanceConfiguration {
    // cluster level
    private static final Integer DEFAULT_CONNECTION_ATTEMPTS_PER_SEC = 100;
    private static final Integer DEFAULT_MAX_CONNECTIONS = 500;
    private static final String DEFAULT_INGRESS_EGRESS_THROUGHPUT_PER_SEC = "30Mi";
    private static final String JVM_OPTIONS_XX = "ExitOnOutOfMemoryError true";

    // broker
    public static final int KAFKA_BROKERS = 3;
    private static final String KAFKA_STORAGE_CLASS = "gp2";
    private static final String KAFKA_CONTAINER_MEMORY = "1Gi";
    private static final String KAFKA_CONTAINER_CPU = "1000m";
    private static final String DEFAULT_KAFKA_VOLUME_SIZE = "1000Gi";
    private static final String KAFKA_JVM_XMS = "512m";
    private static final String KAFKA_JVM_XMX = "512m";

    // zookeeper
    private static final int ZOOKEEPER_NODES = 3;
    private static final String ZOOKEEPER_VOLUME_SIZE = "10Gi";
    private static final String ZOOKEEPER_CONTAINER_MEMORY = "1Gi";
    private static final String ZOOKEEPER_CONTAINER_CPU = "500m";
    private static final String ZOOKEEPER_JVM_XMS = "512m";
    private static final String ZOOKEEPER_JVM_XMX = "512m";

    // exporter
    private static final String KAFKA_EXPORTER_CONTAINER_MEMORY_REQUEST = "128Mi";
    private static final String KAFKA_EXPORTER_CONTAINER_CPU_REQUEST = "500m";
    private static final String KAFKA_EXPORTER_CONTAINER_MEMORY_LIMIT = "256Mi";
    private static final String KAFKA_EXPORTER_CONTAINER_CPU_LIMIT = "1000m";

    protected int connectionAttemptsPerSec = DEFAULT_CONNECTION_ATTEMPTS_PER_SEC;
    protected int maxConnections = DEFAULT_MAX_CONNECTIONS;
    protected String ingressThroughputPerSec = DEFAULT_INGRESS_EGRESS_THROUGHPUT_PER_SEC;
    protected String egressThroughputPerSec = DEFAULT_INGRESS_EGRESS_THROUGHPUT_PER_SEC;
    protected int replicas = KAFKA_BROKERS;
    protected String storageClass = KAFKA_STORAGE_CLASS;
    protected String containerMemory = KAFKA_CONTAINER_MEMORY;
    protected String containerCpu = KAFKA_CONTAINER_CPU;
    protected String volumeSize = DEFAULT_KAFKA_VOLUME_SIZE;
    protected String jvmXms = KAFKA_JVM_XMS;
    protected String jvmXmx = KAFKA_JVM_XMX;
    protected List<String> jvmXx = new ArrayList<>();

    @JsonUnwrapped(prefix = "zoo-keeper.")
    protected ZooKeeper zooKeeper;
    @JsonUnwrapped(prefix = "exporter.")
    protected Exporter exporter;

    public KafkaInstanceConfiguration() {
        this.jvmXx.add(JVM_OPTIONS_XX);
    }

    public int getReplicas() {
        return replicas;
    }

    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }

    public String getStorageClass() {
        return storageClass;
    }

    public void setStorageClass(String storageClass) {
        this.storageClass = storageClass;
    }

    public String getContainerMemory() {
        return containerMemory;
    }

    public void setContainerMemory(String containerMemory) {
        this.containerMemory = containerMemory;
    }

    public String getContainerCpu() {
        return containerCpu;
    }

    public void setContainerCpu(String containerCpu) {
        this.containerCpu = containerCpu;
    }

    public String getVolumeSize() {
        return volumeSize;
    }

    public void setVolumeSize(String volumeSize) {
        this.volumeSize = volumeSize;
    }

    public String getJvmXms() {
        return jvmXms;
    }

    public void setJvmXms(String jvmXms) {
        this.jvmXms = jvmXms;
    }

    public String getJvmXmx() {
        return jvmXmx;
    }

    public void setJvmXmx(String jvmXmx) {
        this.jvmXmx = jvmXmx;
    }

    public List<String> getJvmXx() {
        return jvmXx;
    }

    public void setJvmXx(List<String> jvmXx) {
        this.jvmXx = jvmXx;
    }

    public Map<String, String> getJvmXxMap() {
        return listToMap(this.jvmXx);
    }

    public static class ZooKeeper {
        private int replicas = ZOOKEEPER_NODES;
        private String volumeSize = ZOOKEEPER_VOLUME_SIZE;
        private String containerMemory = ZOOKEEPER_CONTAINER_MEMORY;
        private String containerCpu = ZOOKEEPER_CONTAINER_CPU;
        private String jvmXms = ZOOKEEPER_JVM_XMS;
        private String jvmXmx = ZOOKEEPER_JVM_XMX;
        private List<String> jvmXx = new ArrayList<>();

        public ZooKeeper() {
            this.jvmXx.add(JVM_OPTIONS_XX);
        }

        public int getReplicas() {
            return replicas;
        }
        public void setReplicas(int replicas) {
            this.replicas = replicas;
        }
        public String getVolumeSize() {
            return volumeSize;
        }
        public void setVolumeSize(String volumeSize) {
            this.volumeSize = volumeSize;
        }
        public String getContainerMemory() {
            return containerMemory;
        }
        public void setContainerMemory(String containerMemory) {
            this.containerMemory = containerMemory;
        }
        public String getContainerCpu() {
            return containerCpu;
        }
        public void setContainerCpu(String containerCpu) {
            this.containerCpu = containerCpu;
        }
        public String getJvmXms() {
            return jvmXms;
        }
        public void setJvmXms(String jvmXms) {
            this.jvmXms = jvmXms;
        }
        public String getJvmXmx() {
            return jvmXmx;
        }
        public void setJvmXmx(String jvmXmx) {
            this.jvmXmx = jvmXmx;
        }
        public List<String> getJvmXx() {
            return jvmXx;
        }
        public void setJvmXx(List<String> jvmXx) {
            this.jvmXx = jvmXx;
        }
        public Map<String, String> getJvmXxMap() {
            return listToMap(this.jvmXx);
        }
    }

    public static class Exporter {
        private String containerMemory = KAFKA_EXPORTER_CONTAINER_MEMORY_LIMIT;
        private String containerCpu = KAFKA_EXPORTER_CONTAINER_CPU_LIMIT;
        private String containerRequestMemory = KAFKA_EXPORTER_CONTAINER_MEMORY_REQUEST;
        private String containerRequestCpu = KAFKA_EXPORTER_CONTAINER_CPU_REQUEST;

        public String getContainerMemory() {
            return containerMemory;
        }
        public void setContainerMemory(String containerMemory) {
            this.containerMemory = containerMemory;
        }
        public String getContainerCpu() {
            return containerCpu;
        }
        public void setContainerCpu(String containerCpu) {
            this.containerCpu = containerCpu;
        }
        public String getContainerRequestMemory() {
            return containerRequestMemory;
        }
        public void setContainerRequestMemory(String containerRequestMemory) {
            this.containerRequestMemory = containerRequestMemory;
        }
        public String getContainerRequestCpu() {
            return containerRequestCpu;
        }
        public void setContainerRequestCpu(String containerRequestCpu) {
            this.containerRequestCpu = containerRequestCpu;
        }
    }

    private static Map<String, String> listToMap(List<String> strs) {
        return strs
                .stream()
                .map(e -> e.trim())
                .map(e -> e.split("\\s+"))
                .filter(e -> e[0].trim().length() > 0)
                .collect(Collectors.toMap(e -> e[0], e -> e[1]));
    }

    public int getConnectionAttemptsPerSec() {
        return connectionAttemptsPerSec;
    }

    public void setConnectionAttemptsPerSec(int connectionAttemptsPerSec) {
        this.connectionAttemptsPerSec = connectionAttemptsPerSec;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public String getIngressThroughputPerSec() {
        return ingressThroughputPerSec;
    }

    public void setIngressThroughputPerSec(String ingressThroughputPerSec) {
        this.ingressThroughputPerSec = ingressThroughputPerSec;
    }

    public String getEgressThroughputPerSec() {
        return egressThroughputPerSec;
    }

    public void setEgressThroughputPerSec(String egressThroughputPerSec) {
        this.egressThroughputPerSec = egressThroughputPerSec;
    }

    public ZooKeeper getZooKeeper() {
        return zooKeeper;
    }

    public void setZooKeeper(ZooKeeper zookeeper) {
        this.zooKeeper = zookeeper;
    }

    public Exporter getExporter() {
        return exporter;
    }

    public void setExporter(Exporter exporter) {
        this.exporter = exporter;
    }
}
