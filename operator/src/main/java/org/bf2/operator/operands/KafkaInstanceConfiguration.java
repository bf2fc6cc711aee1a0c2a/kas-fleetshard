package org.bf2.operator.operands;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.quarkus.arc.config.ConfigProperties;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
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
    private static final String KAFKA_CONTAINER_MEMORY = "8Gi";
    private static final String KAFKA_CONTAINER_CPU = "3000m";
    private static final String DEFAULT_KAFKA_VOLUME_SIZE = "1000Gi";
    private static final String KAFKA_JVM_XMS = "3G";
    private static final String KAFKA_JVM_XMX = "3G";

    // zookeeper
    private static final int ZOOKEEPER_NODES = 3;
    private static final String ZOOKEEPER_VOLUME_SIZE = "10Gi";
    private static final String ZOOKEEPER_CONTAINER_MEMORY = "4Gi";
    private static final String ZOOKEEPER_CONTAINER_CPU = "1000m";
    private static final String ZOOKEEPER_JVM_XMS = "2G";
    private static final String ZOOKEEPER_JVM_XMX = "2G";

    // exporter
    private static final String KAFKA_EXPORTER_CONTAINER_MEMORY_REQUEST = "128Mi";
    private static final String KAFKA_EXPORTER_CONTAINER_CPU_REQUEST = "500m";
    private static final String KAFKA_EXPORTER_CONTAINER_MEMORY_LIMIT = "256Mi";
    private static final String KAFKA_EXPORTER_CONTAINER_CPU_LIMIT = "1000m";

    @JsonProperty("kafka.connection-attempts-per-sec")
    protected int connectionAttemptsPerSec = DEFAULT_CONNECTION_ATTEMPTS_PER_SEC;
    @JsonProperty("kafka.max-connections")
    protected int maxConnections = DEFAULT_MAX_CONNECTIONS;
    @JsonProperty("kafka.ingress-throughput-per-sec")
    protected String ingressThroughputPerSec = DEFAULT_INGRESS_EGRESS_THROUGHPUT_PER_SEC;
    @JsonProperty("kafka.egress-throughput-per-sec")
    protected String egressThroughputPerSec = DEFAULT_INGRESS_EGRESS_THROUGHPUT_PER_SEC;
    @JsonProperty("kafka.replicas")
    protected int replicas = KAFKA_BROKERS;
    @JsonProperty("kafka.storage-class")
    protected String storageClass = KAFKA_STORAGE_CLASS;
    @JsonProperty("kafka.container-memory")
    protected String containerMemory = KAFKA_CONTAINER_MEMORY;
    @JsonProperty("kafka.container-cpu")
    protected String containerCpu = KAFKA_CONTAINER_CPU;
    @JsonProperty("kafka.volume-size")
    protected String volumeSize = DEFAULT_KAFKA_VOLUME_SIZE;
    @JsonProperty("kafka.jvm-xms")
    protected String jvmXms = KAFKA_JVM_XMS;
    @JsonProperty("kafka.jvm-xmx")
    protected String jvmXmx = KAFKA_JVM_XMX;
    @JsonProperty("kafka.jvm-xx")
    protected String jvmXx = JVM_OPTIONS_XX;
    @JsonProperty("kafka.enable-quota")
    protected boolean enableQuota = true;

    @JsonUnwrapped(prefix = "kafka.zoo-keeper.")
    protected ZooKeeper zooKeeper;
    @JsonUnwrapped(prefix = "kafka.exporter.")
    protected Exporter exporter;

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

    public String getJvmXx() {
        return this.jvmXx;
    }

    public void setJvmXx(String jvmXx) {
        this.jvmXx = jvmXx;
    }

    @JsonIgnore
    public Map<String, String> getJvmXxMap() {
        return strToMap(this.jvmXx.equals(JVM_OPTIONS_XX) ? this.jvmXx : this.jvmXx + "," + JVM_OPTIONS_XX);
    }

    public static class ZooKeeper {
        @JsonProperty("replicas")
        private int replicas = ZOOKEEPER_NODES;
        @JsonProperty("volume-size")
        private String volumeSize = ZOOKEEPER_VOLUME_SIZE;
        @JsonProperty("container-memory")
        private String containerMemory = ZOOKEEPER_CONTAINER_MEMORY;
        @JsonProperty("container-cpu")
        private String containerCpu = ZOOKEEPER_CONTAINER_CPU;
        @JsonProperty("jvm-xms")
        private String jvmXms = ZOOKEEPER_JVM_XMS;
        @JsonProperty("jvm-xmx")
        private String jvmXmx = ZOOKEEPER_JVM_XMX;
        @JsonProperty("jvm-xx")
        protected String jvmXx = JVM_OPTIONS_XX;

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
        public String getJvmXx() {
            return this.jvmXx;
        }
        public void setJvmXx(String jvmXx) {
            this.jvmXx = jvmXx;
        }
        @JsonIgnore
        public Map<String, String> getJvmXxMap() {
            return strToMap(this.jvmXx.equals(JVM_OPTIONS_XX) ? this.jvmXx : this.jvmXx + "," + JVM_OPTIONS_XX);
        }
    }

    public static class Exporter {
        @JsonProperty("container-memory")
        private String containerMemory = KAFKA_EXPORTER_CONTAINER_MEMORY_LIMIT;
        @JsonProperty("container-cpu")
        private String containerCpu = KAFKA_EXPORTER_CONTAINER_CPU_LIMIT;
        @JsonProperty("container-request-memory")
        private String containerRequestMemory = KAFKA_EXPORTER_CONTAINER_MEMORY_REQUEST;
        @JsonProperty("container-request-cpu")
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

    private static Map<String, String> strToMap(String strs) {
        if (strs != null) {
            return Arrays.stream(strs.split(","))
                    .map(e -> e.trim())
                    .map(e -> e.split("\\s+"))
                    .filter(e -> e[0].trim().length() > 0)
                    .collect(Collectors.toMap(e -> e[0], e -> e[1]));
        }
        return new TreeMap<>();
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

    public boolean isEnableQuota() {
        return enableQuota;
    }

    public void setEnableQuota(boolean enableQuota) {
        this.enableQuota = enableQuota;
    }
}
