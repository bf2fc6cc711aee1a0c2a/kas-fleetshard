package org.bf2.systemtest.framework;

@FunctionalInterface
public interface ThrowableSupplier<T> {
    T get() throws Exception;
}
