package org.bf2.systemtest.framework;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;

/***
 * Annotation for running sequential tests in parallel test suite
 */
@Target(ElementType.METHOD)
@Retention(RUNTIME)
@Execution(ExecutionMode.SAME_THREAD)
@Test
@ResourceLock(value = SYSTEM_PROPERTIES, mode = READ_WRITE)
public @interface SequentialTest {
}
