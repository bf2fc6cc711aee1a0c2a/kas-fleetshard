package org.bf2.operator.operands.test;

import io.quarkus.arc.properties.IfBuildProperty;
import io.strimzi.api.kafka.model.Kafka;

import javax.enterprise.context.ApplicationScoped;

/**
 * Provides same functionalities to get a Kafka resource from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@IfBuildProperty(name = "kafka", stringValue = "test")
public class KafkaCluster extends org.bf2.operator.operands.KafkaCluster {

    @Override
    protected void createOrUpdate(Kafka kafka) {
        return;
    }
}
