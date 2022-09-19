package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.kubernetes.api.model.Quantity;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Defines the capacity provided by a ManagedKafka instance in terms of throughput, connection, data retention and more
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
@JsonInclude(value = JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class ManagedKafkaCapacity {

    private Quantity ingressPerSec;
    private Quantity egressPerSec;
    private Quantity ingressEgressThroughputPerSec;
    private Integer totalMaxConnections;
    private Quantity maxDataRetentionSize;
    private Integer maxPartitions;
    private String maxDataRetentionPeriod;
    private Integer maxConnectionAttemptsPerSec;

    public Quantity getEgressPerSec() {
        if (egressPerSec == null) {
            return ingressEgressThroughputPerSec;
        }
        return egressPerSec;
    }

    public Quantity getIngressPerSec() {
        if (ingressPerSec == null) {
            return ingressEgressThroughputPerSec;
        }
        return ingressPerSec;
    }
}
