package org.bf2.systemtest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.executor.ExecBuilder;
import org.bf2.test.executor.ExecResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Just example test, will be deleted when we start with proper testing
 */

public class ExampleST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(ExampleST.class);

    @Test
    void testExample() {
        ExecResult r = new ExecBuilder().withCommand("ls").exec();
        assertTrue(r.exitStatus());
        kube.client().namespaces().list().getItems().forEach(namespace ->
                LOGGER.info("Namespace: {}", namespace.getMetadata().getName()));
        kube.client().pods().inAnyNamespace().list().getItems().forEach(namespace ->
                LOGGER.info("Pod: {}", namespace.getMetadata().getName()));
        kube.cmdClient().exec("get", "namespaces");
    }
}
