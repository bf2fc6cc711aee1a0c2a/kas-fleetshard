package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.validation.constraints.NotNull;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
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
    private static final Pattern strimziVersionPattern = Pattern.compile("[a-z\\.\\-]*(\\d+\\.\\d+\\.\\d+)(?:-(\\d+))?");
    private static final Comparator<String> strimziComparator = new StrimziVersionComparator();

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

    static class StrimziVersionComparator implements Comparator<String> {
        @Override
        public int compare(String strimziVersion, String otherVersion) {
            Matcher strimziMatcher = strimziVersionPattern.matcher(strimziVersion);
            Matcher testMatcher = strimziVersionPattern.matcher(otherVersion);

            if (strimziMatcher.matches() && testMatcher.matches()) {
                String[] strimziParts = strimziMatcher.group(1).split("\\.");
                String[] testParts = testMatcher.group(1).split("\\.");
                int result;

                for (int i = 0; i < 3; i++) {
                    if ((result = compareInt(strimziParts[i], testParts[i])) != 0) {
                        return result;
                    }
                }

                String strimziSequence = Objects.requireNonNullElse(strimziMatcher.group(2), "");
                String testSequence = Objects.requireNonNullElse(testMatcher.group(2), "");

                return compareInt(strimziSequence, testSequence);
            }

            return strimziVersion.compareTo(otherVersion);
        }

        private int compareInt(String val1, String val2) {
            if (val1.isEmpty()) {
                return -1;
            }
            if (val2.isEmpty()) {
                return 1;
            }
            return Integer.compare(Integer.parseInt(val1), Integer.parseInt(val2));
        }
    }

}
