/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package org.bf2.systemtest.framework.matchers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>A LogHasNoUnexpectedErrors is custom matcher to check log form kubernetes client
 * doesn't have any unexpected errors. </p>
 */
public class LogHasNoUnexpectedErrors extends BaseMatcher<String> {

    private static final Logger LOGGER = LogManager.getLogger(LogHasNoUnexpectedErrors.class);

    @Override
    public boolean matches(Object actualValue) {
        if (!"".equals(actualValue)) {
            if (actualValue.toString().contains("Unhandled Exception")) {
                return false;
            }
            // This pattern is used for split each log ine with stack trace if it's there from some reasons
            List<String> logLineSplitPatterns = Arrays.asList(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2},[0-9]{3}",
                    "[0-9]{2}:[0-9]{2}:[0-9]{2}.[0-9]{3}");
            for (String logLineSplitPattern : logLineSplitPatterns) {
                for (String line : ((String) actualValue).split(logLineSplitPattern)) {
                    if (line.contains("DEBUG") || line.contains("WARN") || line.contains("INFO")) {
                        continue;
                    }
                    if (line.startsWith("java.lang.NullPointerException")) {
                        return false;
                    }
                    String lineLowerCase = line.toLowerCase(Locale.ENGLISH);
                    if (lineLowerCase.contains("error") || lineLowerCase.contains("exception")) {
                        boolean whiteListResult = false;
                        for (LogWhiteList value : LogWhiteList.values()) {
                            Matcher m = Pattern.compile(value.name).matcher(line);
                            if (m.find()) {
                                whiteListResult = true;
                                break;
                            }
                        }
                        if (!whiteListResult) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
        return true;
    }

    @Override
    public void describeTo(Description description) {
        description.appendText("The log should not contain unexpected errors.");
    }

    /**
     * Collections of allowed ERROR or exception messages
     */
    enum LogWhiteList {
        DUMMY("dummy");

        final String name;

        LogWhiteList(String name) {
            this.name = name;
        }
    }
}
