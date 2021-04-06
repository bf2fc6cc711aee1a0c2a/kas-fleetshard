package org.bf2.operator.operands.dev;

import javax.enterprise.context.ApplicationScoped;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Provides same functionalities to get a AdminServer deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@IfBuildProperty(name = "adminserver", stringValue = "dev")
public class AdminServer extends org.bf2.operator.operands.AdminServer {
}
