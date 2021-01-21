package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

import java.util.List;

@Buildable
public class ManagedKafkaStatus {

    private List<ManagedKafkaCondition> conditions;

    public List<ManagedKafkaCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<ManagedKafkaCondition> conditions) {
        this.conditions = conditions;
    }
}
