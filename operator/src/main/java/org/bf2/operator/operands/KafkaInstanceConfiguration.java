package org.bf2.operator.operands;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.utils.Serialization;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Provides an object model that can externalize the operator configuration as a simple map.
 * <br>Relies heavily on jackson processing - do not do anything that will prevent all property
 * keys from being present in default output, such as use JsonInclude non-null on a field/class.
 */
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

    // zookeeper
    private static final int ZOOKEEPER_NODES = 3;
    private static final String ZOOKEEPER_VOLUME_SIZE = "10Gi";
    private static final String ZOOKEEPER_CONTAINER_MEMORY = "4Gi";
    private static final String ZOOKEEPER_CONTAINER_CPU = "1000m";
    private static final String ZOOKEEPER_JVM_XMS = "1G";

    // exporter
    private static final String KAFKA_EXPORTER_CONTAINER_MEMORY_REQUEST = "256Mi";
    private static final String KAFKA_EXPORTER_CONTAINER_CPU_REQUEST = "500m";
    private static final String KAFKA_EXPORTER_CONTAINER_MEMORY_LIMIT = "256Mi";
    private static final String KAFKA_EXPORTER_CONTAINER_CPU_LIMIT = "1000m";

    // canary
    private static final String CANARY_CONTAINER_MEMORY = "64Mi";
    private static final String CANARY_CONTAINER_CPU = "75m";

    // admin server
    private static final String ADMIN_SERVER_CONTAINER_MEMORY = "512Mi";
    private static final String ADMIN_SERVER_CONTAINER_CPU = "500m";

    @JsonUnwrapped(prefix = "managedkafka.kafka.")
    protected Kafka kafka = new Kafka();
    @JsonUnwrapped(prefix = "managedkafka.zookeeper.")
    protected ZooKeeper zookeeper = new ZooKeeper();
    @JsonUnwrapped(prefix = "managedkafka.kafkaexporter.")
    protected Exporter exporter = new Exporter();
    @JsonUnwrapped(prefix = "managedkafka.adminserver.")
    protected AdminServer adminserver = new AdminServer();
    @JsonUnwrapped(prefix = "managedkafka.canary.")
    protected Canary canary = new Canary();

    public Map<String, String> toMap(boolean includeAll) {
        ObjectMapper mapper = Serialization.jsonMapper();
        if (!includeAll) {
            mapper = mapper.copy().setSerializationInclusion(Include.NON_NULL);
        }
        return mapper.convertValue(this, new TypeReference<Map<String, String>>() {});
    }

    public static class Kafka {
        @JsonProperty("connection-attempts-per-sec")
        protected int connectionAttemptsPerSec = DEFAULT_CONNECTION_ATTEMPTS_PER_SEC;
        @JsonProperty("max-connections")
        protected int maxConnections = DEFAULT_MAX_CONNECTIONS;
        @JsonProperty("ingress-throughput-per-sec")
        protected String ingressThroughputPerSec = DEFAULT_INGRESS_EGRESS_THROUGHPUT_PER_SEC;
        @JsonProperty("egress-throughput-per-sec")
        protected String egressThroughputPerSec = DEFAULT_INGRESS_EGRESS_THROUGHPUT_PER_SEC;
        @JsonProperty("replicas")
        protected int replicas = KAFKA_BROKERS;
        @JsonProperty("storage-class")
        protected String storageClass = KAFKA_STORAGE_CLASS;
        @JsonProperty("container-memory")
        protected String containerMemory = KAFKA_CONTAINER_MEMORY;
        @JsonProperty("container-cpu")
        protected String containerCpu = KAFKA_CONTAINER_CPU;
        @JsonProperty("volume-size")
        protected String volumeSize = DEFAULT_KAFKA_VOLUME_SIZE;
        @JsonProperty("jvm-xms")
        protected String jvmXms = KAFKA_JVM_XMS;
        @JsonProperty("jvm-xx")
        protected String jvmXx = JVM_OPTIONS_XX;
        @JsonProperty("enable-quota")
        protected boolean enableQuota = true;
        @JsonUnwrapped(prefix = "acl.")
        protected AccessControl acl = new AccessControl();
        @JsonUnwrapped(prefix = "acl-legacy.")
        protected AccessControl aclLegacy = new AccessControl();
        @JsonProperty("colocate-with-zookeeper")
        protected boolean colocateWithZookeeper = false;
        @JsonProperty("one-instance-per-node")
        protected boolean oneInstancePerNode = false;
        @JsonProperty("maximum-session-lifetime-default")
        protected long maximumSessionLifetimeDefault;

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

        public String getJvmXx() {
            return this.jvmXx;
        }

        public void setJvmXx(String jvmXx) {
            this.jvmXx = jvmXx;
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

        public boolean isEnableQuota() {
            return enableQuota;
        }

        public void setEnableQuota(boolean enableQuota) {
            this.enableQuota = enableQuota;
        }

        @JsonIgnore
        public Map<String, String> getJvmXxMap() {
            return strToMap(JVM_OPTIONS_XX.equals(this.jvmXx) ? this.jvmXx : this.jvmXx + "," + JVM_OPTIONS_XX);
        }

        public AccessControl getAcl() {
            return acl;
        }

        public void setAcl(AccessControl acl) {
            this.acl = acl;
        }

        public AccessControl getAclLegacy() {
            return aclLegacy;
        }

        public void setAclLegacy(AccessControl aclLegacy) {
            this.aclLegacy = aclLegacy;
        }

        public boolean isColocateWithZookeeper() {
            return colocateWithZookeeper;
        }

        public void setColocateWithZookeeper(boolean colocateWithZookeeper) {
            this.colocateWithZookeeper = colocateWithZookeeper;
        }

        public boolean isOneInstancePerNode() {
            return oneInstancePerNode;
        }

        public void setOneInstancePerNode(boolean oneInstancePerNode) {
            this.oneInstancePerNode = oneInstancePerNode;
        }

        public long getMaximumSessionLifetimeDefault() {
            return maximumSessionLifetimeDefault;
        }

        public void setMaximumSessionLifetimeDefault(long maximumSessionLifetimeDefault) {
            this.maximumSessionLifetimeDefault = maximumSessionLifetimeDefault;
        }
    }

    public static class AccessControl {
        @JsonProperty("final-version")
        protected String finalVersion = "0.0.0"; // Default, disables `acl-legacy` configurations
        @JsonProperty("authorizer-class")
        protected String authorizerClass = null;
        @JsonProperty("config-prefix")
        protected String configPrefix = null;
        @JsonProperty("global")
        protected String global = null;
        @JsonProperty("owner")
        protected String owner = null;
        @JsonProperty("resource-operations")
        protected String resourceOperations = null;
        @JsonProperty("allowed-listeners")
        protected String allowedListeners = null;
        @JsonProperty("logging")
        protected String logging = null;
        @JsonUnwrapped(prefix = "logging.suppression-window.")
        protected LoggingSuppressionWindow loggingSuppressionWindow = new LoggingSuppressionWindow();

        public static class LoggingSuppressionWindow {

            @JsonProperty("duration")
            private String duration = null;

            @JsonProperty("eventCount")
            private Integer eventCount = null;

            @JsonProperty("apis")
            private String apis = null;

            public String getDuration() {
                return duration;
            }

            public void setDuration(String duration) {
                this.duration = duration;
            }

            public Integer getEventCount() {
                return eventCount;
            }

            public void setEventCount(Integer eventCount) {
                this.eventCount = eventCount;
            }

            public String getApis() {
                return apis;
            }

            public void setApis(String apis) {
                this.apis = apis;
            }
        }

        public String getFinalVersion() {
            return finalVersion;
        }

        public void setFinalVersion(String finalVersion) {
            this.finalVersion = finalVersion;
        }

        public String getAuthorizerClass() {
            return authorizerClass;
        }

        public void setAuthorizerClass(String authorizerClass) {
            this.authorizerClass = authorizerClass;
        }

        public String getConfigPrefix() {
            return configPrefix;
        }

        public void setConfigPrefix(String configPrefix) {
            this.configPrefix = configPrefix;
        }

        public String getGlobal() {
            return global;
        }

        public void setGlobal(String global) {
            this.global = global;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getResourceOperations() {
            return resourceOperations;
        }

        public void setResourceOperations(String resourceOperations) {
            this.resourceOperations = resourceOperations;
        }

        public String getAllowedListeners() {
            return allowedListeners;
        }

        public void setAllowedListeners(String allowedListeners) {
            this.allowedListeners = allowedListeners;
        }

        public String getLogging() {
            return logging;
        }

        public void setLogging(String level) {
            this.logging = level;
        }

        public LoggingSuppressionWindow getLoggingSuppressionWindow() {
            return loggingSuppressionWindow;
        }

        public void setLoggingSuppressionWindow(LoggingSuppressionWindow loggingSuppressionWindow) {
            this.loggingSuppressionWindow = loggingSuppressionWindow;
        }
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

        public String getJvmXx() {
            return this.jvmXx;
        }

        public void setJvmXx(String jvmXx) {
            this.jvmXx = jvmXx;
        }

        @JsonIgnore
        public Map<String, String> getJvmXxMap() {
            return strToMap(JVM_OPTIONS_XX.equals(this.jvmXx) ? this.jvmXx : this.jvmXx + "," + JVM_OPTIONS_XX);
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
        @JsonProperty("colocate-with-zookeeper")
        protected boolean colocateWithZookeeper = false;

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

        public boolean isColocateWithZookeeper() {
            return colocateWithZookeeper;
        }

        public void setColocateWithZookeeper(boolean colocateWithZookeeper) {
            this.colocateWithZookeeper = colocateWithZookeeper;
        }
    }

    public static class AdminServer {
        @JsonProperty("colocate-with-zookeeper")
        protected boolean colocateWithZookeeper = false;

        @JsonProperty("edge-tls-enabled")
        protected boolean edgeTlsEnabled = false;

        @JsonProperty("container-memory")
        protected String containerMemory = ADMIN_SERVER_CONTAINER_MEMORY;
        @JsonProperty("container-cpu")
        protected String containerCpu = ADMIN_SERVER_CONTAINER_CPU;

        @JsonProperty("rate-limit-enabled")
        protected boolean rateLimitEnabled = true;

        @JsonProperty("rate-limit-concurrent-tcp")
        protected int rateLimitConcurrentTcp = 20;

        @JsonProperty("rate-limit-requests-per-sec")
        protected int rateLimitRequestsPerSec = 40;

        public boolean isColocateWithZookeeper() {
            return colocateWithZookeeper;
        }

        public void setColocateWithZookeeper(boolean colocateWithZookeeper) {
            this.colocateWithZookeeper = colocateWithZookeeper;
        }

        public boolean isEdgeTlsEnabled() {
            return edgeTlsEnabled;
        }

        public void setEdgeTlsEnabled(boolean edgeTlsEnabled) {
            this.edgeTlsEnabled = edgeTlsEnabled;
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

        public boolean isRateLimitEnabled() {
            return rateLimitEnabled;
        }

        public void setRateLimitEnabled(boolean rateLimitEnabled) {
            this.rateLimitEnabled = rateLimitEnabled;
        }

        public int getRateLimitConcurrentTcp() {
            return rateLimitConcurrentTcp;
        }

        public void setRateLimitConcurrentTcp(int rateLimitConcurrentTcp) {
            this.rateLimitConcurrentTcp = rateLimitConcurrentTcp;
        }

        public int getRateLimitRequestsPerSec() {
            return rateLimitRequestsPerSec;
        }

        public void setRateLimitRequestsPerSec(int rateLimitRequestsPerSec) {
            this.rateLimitRequestsPerSec = rateLimitRequestsPerSec;
        }
    }

    public static class Canary {
        @JsonProperty("probe-external-bootstrap-server-host")
        protected boolean probeExternalBootstrapServerHost = false;

        @JsonProperty("colocate-with-zookeeper")
        protected boolean colocateWithZookeeper = false;

        @JsonProperty("container-memory")
        protected String containerMemory = CANARY_CONTAINER_MEMORY;
        @JsonProperty("container-cpu")
        protected String containerCpu = CANARY_CONTAINER_CPU;

        protected String topic;

        @JsonProperty("consumer-group-id")
        protected String consumerGroupId;

        @JsonProperty("client-id")
        protected String clientId;

        public boolean isColocateWithZookeeper() {
            return colocateWithZookeeper;
        }

        public void setColocateWithZookeeper(boolean colocateWithZookeeper) {
            this.colocateWithZookeeper = colocateWithZookeeper;
        }

        public boolean isProbeExternalBootstrapServerHost() {
            return probeExternalBootstrapServerHost;
        }

        public void setProbeExternalBootstrapServerHost(boolean probeExternalBootstrapServerHost) {
            this.probeExternalBootstrapServerHost = probeExternalBootstrapServerHost;
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

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getConsumerGroupId() {
            return consumerGroupId;
        }

        public void setConsumerGroupId(String consumerGroupId) {
            this.consumerGroupId = consumerGroupId;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }
    }

    private static Map<String, String> strToMap(String strs) {
        if (strs != null) {
            return Arrays.stream(strs.split(","))
                    .map(String::trim)
                    .map(e -> e.split("\\s+"))
                    .filter(e -> e[0].trim().length() > 0)
                    .collect(Collectors.toMap(e -> e[0], e -> e[1]));
        }
        return new TreeMap<>();
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public ZooKeeper getZookeeper() {
        return zookeeper;
    }

    public void setZookeeper(ZooKeeper zookeeper) {
        this.zookeeper = zookeeper;
    }

    public Exporter getExporter() {
        return exporter;
    }

    public void setExporter(Exporter exporter) {
        this.exporter = exporter;
    }

    public AdminServer getAdminserver() {
        return adminserver;
    }

    public void setAdminserver(AdminServer adminserver) {
        this.adminserver = adminserver;
    }

    public Canary getCanary() {
        return canary;
    }

    public void setCanary(Canary canary) {
        this.canary = canary;
    }

    public void setColocateWithZookeeper(boolean colocate) {
        getCanary().setColocateWithZookeeper(colocate);
        getExporter().setColocateWithZookeeper(colocate);
        getAdminserver().setColocateWithZookeeper(colocate);
    }
}
