package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class ManagedKafkaAgentStatus {

    private List<ManagedKafkaCondition> conditions;

    private String updatedTimestamp;

    private List<StrimziVersionStatus> strimzi;

    private Map<String, ProfileCapacity> capacity = new LinkedHashMap<>();

}
