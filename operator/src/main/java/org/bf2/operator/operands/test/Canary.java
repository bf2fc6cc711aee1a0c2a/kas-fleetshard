package org.bf2.operator.operands.test;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.quarkus.arc.properties.IfBuildProperty;

import javax.enterprise.context.ApplicationScoped;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@IfBuildProperty(name = "kafka", stringValue = "test")
public class Canary extends org.bf2.operator.operands.Canary {

    @Override
    protected void createOrUpdate(Deployment deployment) {
        return;
    }
}
