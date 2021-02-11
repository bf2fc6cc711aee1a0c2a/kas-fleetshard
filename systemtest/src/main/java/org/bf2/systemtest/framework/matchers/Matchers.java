/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package org.bf2.systemtest.framework.matchers;

import org.hamcrest.Matcher;

public class Matchers {

    private Matchers() {
    }

    /**
     * A matcher checks that log doesn't have unexpected errors
     *
     * @return The matcher.
     */
    public static Matcher<String> logHasNoUnexpectedErrors() {
        return new LogHasNoUnexpectedErrors();
    }
}
