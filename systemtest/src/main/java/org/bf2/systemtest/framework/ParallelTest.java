package org.bf2.systemtest.framework;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;

/***
 * Annotation for running parallel tests in test suite
 * please be sure that you know laws of parallel execution and concurrent programming
 * be sure that you do not use shared resources, and if you use shared resources please work with synchronization
 */
@Target(ElementType.METHOD)
@Retention(RUNTIME)
@Execution(ExecutionMode.CONCURRENT)
@Test
@ResourceLock(value = SYSTEM_PROPERTIES, mode = READ)
public @interface ParallelTest {
}
