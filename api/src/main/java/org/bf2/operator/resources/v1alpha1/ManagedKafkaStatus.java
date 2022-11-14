package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Defines the current status with related conditions of a ManagedKafka instance
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@EqualsAndHashCode
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class ManagedKafkaStatus {

    private List<ManagedKafkaCondition> conditions;
    private List<ManagedKafkaRoute> routes;
    private ManagedKafkaCapacity capacity;
    private Versions versions;
    private String adminServerURI;
    private String updatedTimestamp;
    private List<ServiceAccount> serviceAccounts;

}
