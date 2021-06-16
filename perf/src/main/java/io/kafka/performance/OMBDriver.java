package io.kafka.performance;

import io.openmessaging.benchmark.DriverConfiguration;

public class OMBDriver extends DriverConfiguration {

    private Integer replicationFactor;
    private String topicConfig;
    private String commonConfig;
    private String producerConfig;
    private String consumerConfig;

    public OMBDriver() {
        super();
        name = "Kafka";
        driverClass = "io.openmessaging.benchmark.driver.kafka.KafkaBenchmarkDriver";
    }

    public String getName() {
        return name;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public Integer getReplicationFactor() {
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

    public OMBDriver setReplicationFactor(Integer replicationFactor) {
        this.replicationFactor = replicationFactor;
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

    public OMBDriver setProducerConfig(String producerConfig) {
        this.producerConfig = producerConfig;
        return this;
    }

    public OMBDriver setConsumerConfig(String consumerConfig) {
        this.consumerConfig = consumerConfig;
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
                '}';
    }
}
