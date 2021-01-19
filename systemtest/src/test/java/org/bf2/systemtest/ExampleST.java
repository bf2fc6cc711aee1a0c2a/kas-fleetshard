package org.bf2.systemtest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.executor.ExecBuilder;
import org.bf2.systemtest.executor.ExecResult;
import org.bf2.systemtest.k8s.KubeClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Just example test, will be deleted when we start with proper testing
 */

public class ExampleST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(Environment.class);

    @Test
    void testExample() {
        ExecResult r = new ExecBuilder().withCommand("ls").exec();
        assertTrue(r.exitStatus());
        KubeClient.getInstance().client().namespaces().list().getItems().forEach(namespace ->
                LOGGER.info("Namespace: {}", namespace.getMetadata().getName()));
        KubeClient.getInstance().client().pods().inAnyNamespace().list().getItems().forEach(namespace ->
                LOGGER.info("Pod: {}", namespace.getMetadata().getName()));

    }
}
