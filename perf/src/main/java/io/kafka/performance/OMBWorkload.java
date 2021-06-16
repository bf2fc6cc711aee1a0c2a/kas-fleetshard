package io.kafka.performance;

import io.openmessaging.benchmark.Workload;

public class OMBWorkload extends Workload {
    public OMBWorkload() {
        super();
        testDurationMinutes = Math.toIntExact(Math.max(1, Environment.OMB_TEST_DURATION.toMinutes()));
        warmupDurationMinutes = Math.toIntExact(Math.max(1, Environment.OMB_WARMUP_DURATION.toMinutes()));
    }

    public String getName() {
        return name;
    }

    public Integer getTopics() {
        return topics;
    }

    public Integer getPartitionsPerTopic() {
        return partitionsPerTopic;
    }

    public Integer getMessageSize() {
        return messageSize;
    }

    public String getPayloadFile() {
        return payloadFile;
    }

    public Integer getSubscriptionsPerTopic() {
        return subscriptionsPerTopic;
    }

    public Integer getConsumerPerSubscription() {
        return consumerPerSubscription;
    }

    public Integer getProducersPerTopic() {
        return producersPerTopic;
    }

    public Integer getProducerRate() {
        return producerRate;
    }

    public long getConsumerBacklogSizeGB() {
        return consumerBacklogSizeGB;
    }

    public Integer getTestDurationMinutes() {
        return testDurationMinutes;
    }

    public Integer getWarmupDurationMinutes() {
        return warmupDurationMinutes;
    }

    public OMBWorkload setName(String name) {
        this.name = name;
        return this;
    }

    public OMBWorkload setTopics(Integer topics) {
        this.topics = topics;
        return this;
    }

    public OMBWorkload setPartitionsPerTopic(Integer partitionsPerTopic) {
        this.partitionsPerTopic = partitionsPerTopic;
        return this;
    }

    public OMBWorkload setMessageSize(Integer messageSize) {
        this.messageSize = messageSize;
        return this;
    }

    public OMBWorkload setPayloadFile(String payloadFile) {
        this.payloadFile = payloadFile;
        return this;
    }

    public OMBWorkload setSubscriptionsPerTopic(Integer subscriptionsPerTopic) {
        this.subscriptionsPerTopic = subscriptionsPerTopic;
        return this;
    }

    public OMBWorkload setConsumerPerSubscription(Integer consumerPerSubscription) {
        this.consumerPerSubscription = consumerPerSubscription;
        return this;
    }

    public OMBWorkload setProducersPerTopic(Integer producersPerTopic) {
        this.producersPerTopic = producersPerTopic;
        return this;
    }

    public OMBWorkload setProducerRate(Integer producerRate) {
        this.producerRate = producerRate;
        return this;
    }

    public OMBWorkload setConsumerBacklogSizeGB(long consumerBacklogSizeGB) {
        this.consumerBacklogSizeGB = consumerBacklogSizeGB;
        return this;
    }

    public OMBWorkload setTestDurationMinutes(Integer testDurationMinutes) {
        this.testDurationMinutes = testDurationMinutes;
        return this;
    }

    public OMBWorkload setWarmupDurationMinutes(Integer warmupDurationMinutes) {
        this.warmupDurationMinutes = warmupDurationMinutes;
        return this;
    }

    @Override
    public String toString() {
        return "OMBWorkload{" +
                "name='" + name + '\'' +
                ", topics=" + topics +
                ", partitionsPerTopic=" + partitionsPerTopic +
                ", messageSize=" + messageSize +
                ", payloadFile='" + payloadFile + '\'' +
                ", subscriptionsPerTopic=" + subscriptionsPerTopic +
                ", consumerPerSubscription=" + consumerPerSubscription +
                ", producersPerTopic=" + producersPerTopic +
                ", producerRate=" + producerRate +
                ", consumerBacklogSizeGB=" + consumerBacklogSizeGB +
                ", testDurationMinutes=" + testDurationMinutes +
                ", warmupDurationMinutes=" + warmupDurationMinutes +
                '}';
    }
}
