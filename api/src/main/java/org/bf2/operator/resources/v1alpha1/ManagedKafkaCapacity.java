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
public class ManagedKafkaCapacity {

    @Setter private Quantity ingressPerSec;
    @Setter private Quantity egressPerSec;
    @Getter @Setter private Quantity ingressEgressThroughputPerSec;
    @Getter @Setter private Integer totalMaxConnections;
    @Getter @Setter private Quantity maxDataRetentionSize;
    @Getter @Setter private Integer maxPartitions;
    @Getter @Setter private String maxDataRetentionPeriod;
    @Getter @Setter private Integer maxConnectionAttemptsPerSec;

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
