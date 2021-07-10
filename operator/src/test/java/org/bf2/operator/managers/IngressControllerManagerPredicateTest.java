package org.bf2.operator.managers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * This is a separate test class than IngressControllerManagerTest so that no
 * needless setup is run for each test.
 */
public class IngressControllerManagerPredicateTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "my-cluster-kafka-0",
            "my-cluster-kafka-1",
            "my-cluster-kafka-10",
    })
    public void testIsBrokerMatches(String goodInput) {
        assertTrue(IngressControllerManager.IS_BROKER.test(goodInput));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "kafka-1",
            "my-cluster-kafka-",
            "my-cluster-kafka-a",
            "my-cluster-kafka-1a",
            "my-cluster-kafka-a1",
            "my-cluster-kafka-bootstrap",
            "my-cluster-admin-server",
    })
    public void testIsBrokerDoesntMatch(String badInput) {
        assertFalse(IngressControllerManager.IS_BROKER.test(badInput));
    }

}
