package org.bf2.performance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.api.model.Quantity;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdopterProfile {
    /*
     * This is the MK default, not the Kafka default
     */
    public static final Integer DEFAULT_WINDOW_NUM_SAMPLES = 30;

    /*
     * This is the MK default, not the Kafka default
     */
    public static final Integer DEFAULT_WINDOW_SIZE_SECONDS = 2;

    // resource values
    private final Quantity zookeeperContainerMem;
    private final Quantity kafkaContainerMem;
    private final Quantity kafkaJavaMem;
    private final Quantity zookeeperJavaMem;
    private final Quantity zookeeperCpu;
    private final Quantity kafkaCpu;

    // TODO other config overrides - not yet used

    private Integer windowNum = DEFAULT_WINDOW_NUM_SAMPLES;
    private Integer windowSizeSeconds = DEFAULT_WINDOW_SIZE_SECONDS;

    private Long storageQuotaSoftBytes;
    private Long storageQuotaHardBytes;
    private Integer storageCheckInterval;

    /* settings not in the operator
    .addToConfig("retention.ms", 300000)
    .addToConfig("segment.bytes", 10737418240L)
    */

    /* settings in the operator
    .addToConfig("transaction.state.log.min.isr", 2)
    .addToConfig("transaction.state.log.replication.factor", 3)
    */

    public static final AdopterProfile VALUE_PROD = new AdopterProfile(
            Quantity.parse("4Gi"), Quantity.parse("2Gi"), Quantity.parse("1000m"),
            Quantity.parse("8Gi"), Quantity.parse("3Gi"), Quantity.parse("2000m"));

    public static final AdopterProfile SMALL_VALUE_PROD = new AdopterProfile(
            Quantity.parse("1Gi"), Quantity.parse("500Mi"), Quantity.parse("500m"),
            Quantity.parse("1Gi"), Quantity.parse("500Mi"), Quantity.parse("1000m"));

    public static final AdopterProfile TYPE_KICKER = new AdopterProfile(
            Quantity.parse("2Gi"), Quantity.parse("1Gi"), Quantity.parse("500m"),
            Quantity.parse("2Gi"), Quantity.parse("1Gi"), Quantity.parse("500m"));

    public AdopterProfile(Quantity zookeeperContainerMem, Quantity zookeeperJavaMem, Quantity zookeeperCpu, Quantity kafkaContainerMem, Quantity kafkaJavaMem, Quantity kafkaCpu) {
        this.zookeeperContainerMem = zookeeperContainerMem;
        this.kafkaContainerMem = kafkaContainerMem;
        this.kafkaJavaMem = kafkaJavaMem;
        this.zookeeperJavaMem = zookeeperJavaMem;
        this.zookeeperCpu = zookeeperCpu;
        this.kafkaCpu = kafkaCpu;
    }

    public Quantity getZookeeperContainerMem() {
        return zookeeperContainerMem;
    }

    public Quantity getKafkaContainerMem() {
        return kafkaContainerMem;
    }

    public Quantity getKafkaJavaMem() {
        return kafkaJavaMem;
    }

    public Quantity getZookeeperJavaMem() {
        return zookeeperJavaMem;
    }

    public Quantity getZookeeperCpu() {
        return zookeeperCpu;
    }

    public Quantity getKafkaCpu() {
        return kafkaCpu;
    }

    public Integer getWindowNum() {
        return this.windowNum;
    }

    public Integer getWindowSizeSeconds() {
        return windowSizeSeconds;
    }

    public Long getStorageQuotaSoftBytes() {
        return storageQuotaSoftBytes;
    }

    public Long getStorageQuotaHardBytes() {
        return storageQuotaHardBytes;
    }

    public Integer getStorageCheckInterval() {
        return storageCheckInterval;
    }

}
