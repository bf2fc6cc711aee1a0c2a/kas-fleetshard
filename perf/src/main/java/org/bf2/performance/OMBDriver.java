package org.bf2.performance;

import io.openmessaging.benchmark.driver.kafka.Config;
import org.bf2.systemtest.framework.SecurityUtils;

public class OMBDriver extends Config {

    public String name;
    public String driverClass;

    public OMBDriver() {
        super();
        reset = true; // delete all test topics before each run
        name = "Kafka";
        driverClass = "io.openmessaging.benchmark.driver.kafka.KafkaBenchmarkDriverWithMetrics";
    }

    public String getName() {
        return name;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public short getReplicationFactor() {
        return replicationFactor;
    }

    public String getTopicConfig() {
        return topicConfig;
    }

    public String getCommonConfig() {
        return commonConfig;
    }

    public String getProducerConfig() {
        return producerConfig;
    }

    public String getConsumerConfig() {
        return consumerConfig;
    }

    public OMBDriver setReplicationFactor(int replicationFactor) {
        this.replicationFactor = (short)replicationFactor;
        return this;
    }

    public OMBDriver setTopicConfig(String topicConfig) {
        this.topicConfig = topicConfig;
        return this;
    }

    public OMBDriver setCommonConfig(String commonConfig) {
        this.commonConfig = commonConfig;
        return this;
    }

    public OMBDriver setCommonConfigWithBootstrapUrl(String url) {
        this.commonConfig = String.format("bootstrap.servers=%s%nsecurity.protocol=SSL%nssl.truststore.password=%s%nssl.truststore.type=JKS%nssl.truststore.location=/cert/listener.jks%n", url, SecurityUtils.TRUSTSTORE_PASSWORD);
        return this;
    }

    public OMBDriver setProducerConfig(String producerConfig) {
        this.producerConfig = producerConfig;
        return this;
    }

    public OMBDriver setConsumerConfig(String consumerConfig) {
        this.consumerConfig = consumerConfig;
        return this;
    }

    public OMBDriver reset(boolean b) {
        this.reset = b;
        return this;
    }

    @Override
    public String toString() {
        return "OMBDriver{" +
                "name='" + name + '\'' +
                ", driverClass='" + driverClass + '\'' +
                ", replicationFactor=" + replicationFactor +
                ", topicConfig='" + topicConfig + '\'' +
                ", commonConfig='" + commonConfig + '\'' +
                ", producerConfig='" + producerConfig + '\'' +
                ", consumerConfig='" + consumerConfig + '\'' +
                ", reset='" + reset + '\'' +
                '}';
    }
}
