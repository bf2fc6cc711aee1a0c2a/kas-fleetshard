package org.bf2.operator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManagedKafkaKeysTest {

    @Test
    // Trivial test to meet package coverage check
    void testForKey() {
        assertEquals(ManagedKafkaKeys.MK_NAMESPACE + "test", ManagedKafkaKeys.forKey("test"));
    }

}
