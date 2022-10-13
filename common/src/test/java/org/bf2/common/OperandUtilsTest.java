package org.bf2.common;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OperandUtilsTest {

    @Test public void testGetOrDefault() {
        assertEquals("1", OperandUtils.getOrDefault(null, "key", "1"));
        assertEquals("1", OperandUtils.getOrDefault(Map.of(), "key", "1"));
        assertEquals("value", OperandUtils.getOrDefault(Map.of("key", "value"), "key", "1"));
    }


}
