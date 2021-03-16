package org.bf2.operator.operands.dev;

import io.quarkus.arc.properties.IfBuildProperty;

import javax.enterprise.context.ApplicationScoped;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@IfBuildProperty(name = "canary", stringValue = "dev")
public class Canary extends org.bf2.operator.operands.Canary {

}
