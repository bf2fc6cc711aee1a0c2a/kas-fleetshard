package org.bf2.operator.resources.v1alpha1;

import java.io.Serializable;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;

public class StrimziVersionComparator implements Comparator<String>, Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public int compare(String strimziVersion, String otherVersion) {
        Matcher strimziMatcher = Versions.strimziVersionPattern.matcher(strimziVersion);
        Matcher testMatcher = Versions.strimziVersionPattern.matcher(otherVersion);

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
