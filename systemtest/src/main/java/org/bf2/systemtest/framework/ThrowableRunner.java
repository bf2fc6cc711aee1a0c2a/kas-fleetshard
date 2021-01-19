/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package org.bf2.systemtest.framework;

@FunctionalInterface
public interface ThrowableRunner {
    void run() throws Exception;
}
