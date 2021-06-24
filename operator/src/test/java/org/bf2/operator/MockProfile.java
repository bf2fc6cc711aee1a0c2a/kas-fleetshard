package org.bf2.operator;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

import static java.util.Map.entry;

public class MockProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = Map.ofEntries(
            entry("quarkus.scheduler.enabled", "false")
        );
        return overrides;
    }

}
