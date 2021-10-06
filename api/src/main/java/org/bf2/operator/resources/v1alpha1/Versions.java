package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.validation.constraints.NotNull;

import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents different versions supported by the ManagedKafka instance
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Versions {
    static final Pattern strimziVersionPattern = Pattern.compile("[a-z\\.\\-]*(\\d+\\.\\d+\\.\\d+)(?:-(\\d+))?");
    private static final Comparator<String> strimziComparator = new StrimziVersionComparator();

    public static final String STRIMZI_CLUSTER_OPERATOR_V0_23_0_4 = "strimzi-cluster-operator.v0.23.0-4";

    @NotNull
    private String kafka;
    @NotNull
    private String strimzi;

    public String getKafka() {
        return kafka;
    }

    public void setKafka(String kafka) {
        this.kafka = kafka;
    }

    public String getStrimzi() {
        return strimzi;
    }

    public void setStrimzi(String strimzi) {
        this.strimzi = strimzi;
    }

    public boolean isStrimziVersionIn(String... versions) {
        Matcher m = strimziVersionPattern.matcher(getStrimzi());
        if (m.matches()) {
            String currentVersion = m.group(1);
            return currentVersion != null && Arrays.stream(versions).anyMatch(currentVersion::startsWith);
        }
        return false;
    }

    public int compareStrimziVersionTo(String version) {
        return strimziComparator.compare(getStrimzi(), version);
    }

}
