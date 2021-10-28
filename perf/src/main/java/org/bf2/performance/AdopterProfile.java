package org.bf2.performance;

import org.bf2.operator.operands.KafkaInstanceConfiguration;

public class AdopterProfile {
    public static final boolean BROKER_COLLOCATED_WITH_ZOOKEEPER = true;
    public static final String STANDARD_ZOOKEEPER_CONTAINER_SIZE = "4Gi";
    public static final String STANDARD_ZOOKEEPER_VM_SIZE = "1G";
    public static final String STANDARD_ZOOKEEPER_CPU = "1000m";

    public static final KafkaInstanceConfiguration VALUE_PROD = buildProfile(
            STANDARD_ZOOKEEPER_CONTAINER_SIZE, STANDARD_ZOOKEEPER_VM_SIZE, STANDARD_ZOOKEEPER_CPU,
            "8Gi", "3G", "3000m", 3);

    public static final KafkaInstanceConfiguration SMALL_VALUE_PROD = buildProfile(
            "1Gi", "500M", "500m",
            "1Gi", "500M", "1000m", 3);

    public static final KafkaInstanceConfiguration TYPE_KICKER = buildProfile(
            "2Gi", "1G", "500m",
            "2Gi", "1G", "500m", 3);

    public static KafkaInstanceConfiguration buildProfile(String zookeeperContainerMemory, String zookeeperJavaMemory,
            String zookeeperCpu, String kafkaContainerMemory, String kafkaJavaMemory, String kafkaCpu, int numOfBrokers) {
        KafkaInstanceConfiguration config = new KafkaInstanceConfiguration();
        config.getKafka().setReplicas(numOfBrokers);
        config.getKafka().setMaxConnections(Integer.MAX_VALUE);
        config.getKafka().setConnectionAttemptsPerSec(Integer.MAX_VALUE);
        config.getKafka().setOneInstancePerNode(true);
        config.getKafka().setColocateWithZookeeper(BROKER_COLLOCATED_WITH_ZOOKEEPER);
        config.setColocateWithZookeeper(BROKER_COLLOCATED_WITH_ZOOKEEPER);
        config.getKafka().setContainerMemory(kafkaContainerMemory);
        config.getKafka().setContainerCpu(kafkaCpu);
        config.getKafka().setJvmXms(kafkaJavaMemory);
        config.getKafka().setEnableQuota(false);
        config.getZookeeper().setContainerCpu(zookeeperCpu);
        config.getZookeeper().setContainerMemory(zookeeperContainerMemory);
        config.getZookeeper().setJvmXms(zookeeperJavaMemory);
        config.getKafka().getAcl().setAllowedListeners("TLS-9093,SRE-9096"); // by-pass canary acl
        config.getKafka().getAcl().setGlobal("default=true;permission=allow;topic=*;operations=all \n"
                + "default=true;permission=allow;group=*;operations=all \n"
                + "default=true;permission=allow;transactional_id=*;operations=all \n"
                + "default=true;permission=allow;cluster=*;operations=describe \n"
                + "permission=allow;cluster=*;operations=idempotent_write \n"
                + "permission=deny;cluster=*;operations-except=alter,describe,idempotent_write");
        return config;
    }
}

