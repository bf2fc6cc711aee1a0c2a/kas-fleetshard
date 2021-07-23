package org.bf2.resources;

import org.bf2.operator.resources.v1alpha1.Versions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VersionsTest {

    @Test
    public void testVersion() {
        Versions versions = new Versions();

        versions.setStrimzi("0.22.1");
        assertTrue(versions.isStrimziVersionIn(Versions.VERSION_0_22));

        versions.setStrimzi("strimzi-cluster-operator.v0.22.1");
        assertTrue(versions.isStrimziVersionIn(Versions.VERSION_0_22));

        versions.setStrimzi("strimzi-cluster-operator.v0.22.1-6");
        assertTrue(versions.isStrimziVersionIn(Versions.VERSION_0_22));

        versions.setStrimzi("strimzi-cluster-operator.v0.23.0");
        assertFalse(versions.isStrimziVersionIn(Versions.VERSION_0_22));
    }
}
