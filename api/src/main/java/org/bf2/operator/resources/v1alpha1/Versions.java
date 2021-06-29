package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.validation.constraints.NotNull;

import java.util.Arrays;
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
    public static String VERSION_0_22 = "0.22";
    private static Pattern strimziVersionPattern = Pattern.compile("[a-z\\.\\-]*(\\d+\\.\\d+\\.\\d+)");

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
            return Arrays.stream(versions).anyMatch( v -> currentVersion.startsWith(v));
        }
        return false;
    }
}
