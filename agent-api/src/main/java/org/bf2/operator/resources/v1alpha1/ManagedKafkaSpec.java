package org.bf2.operator.resources.v1alpha1;

public class ManagedKafkaSpec {

    private KafkaInstance kafkaInstance;
    private boolean deleted;

    public KafkaInstance getKafkaInstance() {
        return kafkaInstance;
    }

    public void setKafkaInstance(KafkaInstance kafkaInstance) {
        this.kafkaInstance = kafkaInstance;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
