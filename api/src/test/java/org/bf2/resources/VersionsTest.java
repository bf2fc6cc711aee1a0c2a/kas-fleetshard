package org.bf2.resources;

import org.bf2.operator.resources.v1alpha1.Versions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VersionsTest {

    @ParameterizedTest
    @CsvSource({
        "strimzi-cluster-operator.v0.22.1-6, 0.22.1-5, GT",
        "strimzi-cluster-operator.v0.22.1-6, 0.22.1-6, EQ",
        "strimzi-cluster-operator.v0.22.1-6, 0.22.1-7, LT",
        "strimzi-cluster-operator.v0.23.0,   0.23.0-1, LT",
        "strimzi-cluster-operator.v0.23.1,   0.23.0-1, GT",
        "0.23.1,                             0.22.1,   GT",
        ".23.1,                              .24.1,    LT",
        "0.23.1-0,                           0.23.1,   GT",
        "0.22,                               0.23.0-1, LT"
    })
    void testCompareStrimziVersionToMajor(String strimziVersion, String compareToVersion, String expectation) {
        Versions versions = new Versions();
        versions.setStrimzi(strimziVersion);

        switch (expectation) {
        case "LT":
            assertTrue(versions.compareStrimziVersionTo(compareToVersion) < 0);
            break;
        case "EQ":
            assertEquals(0, versions.compareStrimziVersionTo(compareToVersion));
            break;
        case "GT":
            assertTrue(versions.compareStrimziVersionTo(compareToVersion) > 0);
            break;
        }
    }
}
