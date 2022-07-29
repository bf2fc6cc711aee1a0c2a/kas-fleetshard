package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false)
@ToString
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProfileCapacity {

    private Integer maxUnits;
    private Integer remainingUnits;

    public Integer getMaxUnits() {
        return maxUnits;
    }

    public void setMaxUnits(Integer max) {
        this.maxUnits = max;
    }

    public Integer getRemainingUnits() {
        return remainingUnits;
    }

    public void setRemainingUnits(Integer remaining) {
        this.remainingUnits = remaining;
    }

}
