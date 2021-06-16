package org.bf2.performance;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Repeatable(value = ManagedKafkaAssertions.class)
@Retention(value = RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD })
public @interface ManagedKafkaAssertion {
    String instanceType();
    int minWorkers();
    int expectedMinKafkaInstances();
}

@Retention(value = RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD })
@interface ManagedKafkaAssertions {
    ManagedKafkaAssertion[] value();
}