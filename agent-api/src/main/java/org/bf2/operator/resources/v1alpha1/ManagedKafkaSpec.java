package org.bf2.operator.resources.v1alpha1;

public class ManagedKafkaSpec {

    private KafkaInstance kafkaInstance;

    public KafkaInstance getKafkaInstance() {
        return kafkaInstance;
    }

    public void setKafkaInstance(KafkaInstance kafkaInstance) {
        this.kafkaInstance = kafkaInstance;
    }
}
