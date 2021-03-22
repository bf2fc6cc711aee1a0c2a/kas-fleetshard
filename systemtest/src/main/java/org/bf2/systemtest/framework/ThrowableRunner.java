package org.bf2.systemtest.framework;

@FunctionalInterface
public interface ThrowableRunner {
    void run() throws Exception;
}
