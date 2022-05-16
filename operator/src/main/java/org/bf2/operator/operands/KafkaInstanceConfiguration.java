package org.bf2.operator.operands;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Provides an object model that can externalize the operator configuration as a simple map.
 * <br>Relies heavily on jackson processing - do not do anything that will prevent all property
 * keys from being present in default output, such as use JsonInclude non-null on a field/class.
 */
public class KafkaInstanceConfiguration {
    // cluster level
    private static final String JVM_OPTIONS_XX = "ExitOnOutOfMemoryError true";

    // broker
    private static final String KAFKA_STORAGE_CLASS = "gp2";

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
    @JsonUnwrapped(prefix = "managedkafka.storage.")
    protected Storage storage = new Storage();

    public Map<String, String> toMap(boolean includeAll) {
        ObjectMapper mapper = new ObjectMapper();
        if (!includeAll) {
            mapper = mapper.copy().setSerializationInclusion(Include.NON_NULL);
        }
        return mapper.convertValue(this, new TypeReference<Map<String, String>>() {});
    }

    public static class Storage {
        @JsonProperty("check-interval")
        protected int checkInterval;

        @JsonProperty("safety-factor")
        protected int safetyFactor;

        @JsonProperty("min-margin")
        protected Quantity minMargin;

        @JsonProperty("formatting-overhead")
        protected double formattingOverhead;


        public int getCheckInterval() {
            return checkInterval;
        }

        public void setCheckInterval(int checkInterval) {
            this.checkInterval = checkInterval;
        }

        public int getSafetyFactor() {
            return safetyFactor;
        }

        public void setSafetyFactor(int safetyFactor) {
            this.safetyFactor = safetyFactor;
        }

        public Quantity getMinMargin() {
            return minMargin;
        }

        public void setMinMargin(Quantity minMargin) {
            this.minMargin = minMargin;
        }

        public double getFormattingOverhead() {
            return formattingOverhead;
        }

        public void setFormattingOverhead(double formattingOverhead) {
            this.formattingOverhead = formattingOverhead;
        }

    }

    public static class Container {
        @JsonProperty("container-request-memory")
        protected String containerRequestMemory;
        @JsonProperty("container-request-cpu")
        protected String containerRequestCpu;
        @JsonProperty("container-memory")
        protected String containerMemory;
        @JsonProperty("container-cpu")
        protected String containerCpu;

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

        public String getContainerRequestCpu() {
            return containerRequestCpu;
        }

        public String getContainerRequestMemory() {
            return containerRequestMemory;
        }

        public void setContainerRequestCpu(String containerRequestCpu) {
            this.containerRequestCpu = containerRequestCpu;
        }

        public void setContainerRequestMemory(String containerRequestMemory) {
            this.containerRequestMemory = containerRequestMemory;
        }

        public ResourceRequirements buildResources() {
            Quantity mem = new Quantity(getContainerMemory());
            Quantity cpu = new Quantity(getContainerCpu());
            Quantity memRequest = new Quantity(Objects.requireNonNullElse(getContainerRequestMemory(), getContainerMemory()));
            Quantity cpuRequest = new Quantity(Objects.requireNonNullElse(getContainerRequestCpu(), getContainerCpu()));
            return new ResourceRequirementsBuilder()
                    .addToRequests("memory", memRequest)
                    .addToRequests("cpu", cpuRequest)
                    .addToLimits("memory", mem)
                    .addToLimits("cpu", cpu)
                    .build();
        }
    }

    public static class Kafka extends Container {
        @JsonProperty("connection-attempts-per-sec")
        protected int connectionAttemptsPerSec;
        @JsonProperty("max-connections")
        protected int maxConnections;
        @JsonProperty("partition-capacity")
        protected int partitionCapacity;
        @JsonProperty("partition-limit-enforced")
        protected boolean partitionLimitEnforced = true;
        @JsonProperty("scaling-and-replication-factor")
        protected int scalingAndReplicationFactor;
        @JsonProperty("ingress-per-sec")
        protected String ingressPerSec;
        @JsonProperty("egress-per-sec")
        protected String egressPerSec;
        @JsonProperty("storage-class")
        protected String storageClass = KAFKA_STORAGE_CLASS;
        @JsonProperty("volume-size")
        protected String volumeSize;
        @JsonProperty("jvm-xms")
        protected String jvmXms;
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
        @JsonProperty("message-max-bytes")
        protected int messageMaxBytes;
        @JsonProperty("replicas-override")
        protected Integer replicasOverride;
        @JsonProperty("quota-callback-usage-metrics-topic")
        private String quotaCallbackUsageMetricsTopic;
        @JsonProperty("quota-callback-quota-policy-check-interval")
        private int quotaCallbackQuotaPolicyCheckInterval;
        @JsonProperty("quota-callback-quota-kafka-clientid-prefix")
        private String quotaCallbackQuotaKafkaClientidPrefix;

        public String getStorageClass() {
            return storageClass;
        }

        public void setStorageClass(String storageClass) {
            this.storageClass = storageClass;
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

        public String getIngressPerSec() {
            return ingressPerSec;
        }

        public void setIngressPerSec(String ingressPerSec) {
            this.ingressPerSec = ingressPerSec;
        }

        public String getEgressPerSec() {
            return egressPerSec;
        }

        public void setEgressPerSec(String egressPerSec) {
            this.egressPerSec = egressPerSec;
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

        public int getMessageMaxBytes() {
            return messageMaxBytes;
        }

        public void setMessageMaxBytes(int maxMessageBytes) {
            this.messageMaxBytes = maxMessageBytes;
        }

        public int getPartitionCapacity() {
            return partitionCapacity;
        }

        public void setPartitionCapacity(int partitionCapacity) {
            this.partitionCapacity = partitionCapacity;
        }

        public boolean isPartitionLimitEnforced() {
            return partitionLimitEnforced;
        }

        public void setPartitionLimitEnforced(boolean partitionLimitEnforced) {
            this.partitionLimitEnforced = partitionLimitEnforced;
        }

        public int getScalingAndReplicationFactor() {
            return scalingAndReplicationFactor;
        }

        public void setScalingAndReplicationFactor(int scalingAndReplicationFactor) {
            this.scalingAndReplicationFactor = scalingAndReplicationFactor;
        }

        public Integer getReplicasOverride() {
            return replicasOverride;
        }

        public void setReplicasOverride(Integer replicasOverride) {
            this.replicasOverride = replicasOverride;
        }


        public String getQuotaCallbackUsageMetricsTopic() {
            return quotaCallbackUsageMetricsTopic;
        }

        public void setQuotaCallbackUsageMetricsTopic(String quotaCallbackUsageMetricsTopic) {
            this.quotaCallbackUsageMetricsTopic = quotaCallbackUsageMetricsTopic;
        }

        public int getQuotaCallbackQuotaPolicyCheckInterval() {
            return quotaCallbackQuotaPolicyCheckInterval;
        }

        public void setQuotaCallbackQuotaPolicyCheckInterval(int quotaCallbackQuotaPolicyCheckInterval) {
            this.quotaCallbackQuotaPolicyCheckInterval = quotaCallbackQuotaPolicyCheckInterval;
        }


        public String getQuotaCallbackQuotaKafkaClientIdPrefix() {
            return quotaCallbackQuotaKafkaClientidPrefix;
        }

        public void setQuotaCallbackQuotaKafkaClientidPrefix(String quotaCallbackQuotaKafkaClientidPrefix) {
            this.quotaCallbackQuotaKafkaClientidPrefix = quotaCallbackQuotaKafkaClientidPrefix;
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
        @JsonProperty("private-prefix")
        protected String privatePrefix = null;

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

        public String getPrivatePrefix() {
            return privatePrefix;
        }

        public void setPrivatePrefix(String privatePrefix) {
            this.privatePrefix = privatePrefix;
        }
    }

    public static class ZooKeeper extends Container {
        @JsonProperty("replicas")
        private int replicas;
        @JsonProperty("volume-size")
        private String volumeSize;
        @JsonProperty("jvm-xms")
        private String jvmXms;
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

    public static class Exporter extends Container {
        @JsonProperty("colocate-with-zookeeper")
        protected boolean colocateWithZookeeper = false;

        public boolean isColocateWithZookeeper() {
            return colocateWithZookeeper;
        }

        public void setColocateWithZookeeper(boolean colocateWithZookeeper) {
            this.colocateWithZookeeper = colocateWithZookeeper;
        }
    }

    public static class AdminServer extends Container {
        @JsonProperty("colocate-with-zookeeper")
        protected boolean colocateWithZookeeper = false;

        @JsonProperty("edge-tls-enabled")
        protected boolean edgeTlsEnabled = false;

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

    public static class Canary extends Container {
        @JsonProperty("probe-external-bootstrap-server-host")
        protected boolean probeExternalBootstrapServerHost = false;

        @JsonProperty("colocate-with-zookeeper")
        protected boolean colocateWithZookeeper = false;

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

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public void setColocateWithZookeeper(boolean colocate) {
        getCanary().setColocateWithZookeeper(colocate);
        getExporter().setColocateWithZookeeper(colocate);
        getAdminserver().setColocateWithZookeeper(colocate);
    }
}
