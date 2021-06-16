package org.bf2.performance;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.performance.data.OpenshiftClusterData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ManagedKafkaStateAssertionParameterResolver implements ParameterResolver, AfterEachCallback {
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaStateAssertionParameterResolver.class);
    private final ManagedKafkaState managedKafkaState = new ManagedKafkaState();

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == ManagedKafkaState.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return managedKafkaState;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (context.getExecutionException().isEmpty()) {
            TestMetadataCapture instance = TestMetadataCapture.getInstance();
            OpenshiftClusterData openshiftClusterData = instance.getKafkaClusterData();
            Optional<Method> testMethod = context.getTestMethod();
            if (openshiftClusterData != null && testMethod.isPresent()) {
                getManagedKafkaAssertionStream(openshiftClusterData, testMethod)
                        .filter(a -> openshiftClusterData.getCountOfWorkers() >= a.minWorkers())
                        .forEach(this::testAssertion);
            }
        }
    }

    private void testAssertion(ManagedKafkaAssertion a) {
        LOGGER.info("Testing assertion: instance type: {}, expected minimum kafka instances: {}, actual number of instances: {}", a.instanceType(), a.expectedMinKafkaInstances(), managedKafkaState.getKafkaInstances());
        Assertions.assertTrue(managedKafkaState.getKafkaInstances() >= a.expectedMinKafkaInstances(),
                String.format("Too few kafka instances (%d) (expecting %d) on kafka cluster with worker nodes: %s instance type: %s",
                        managedKafkaState.getKafkaInstances(),
                        a.expectedMinKafkaInstances(),
                        a.minWorkers(), a.instanceType()));
    }

    public static Optional<Integer> getLargestKnownInstanceCount(TestInfo info) {
        Optional<Method> testMethod = info.getTestMethod();
        TestMetadataCapture instance = TestMetadataCapture.getInstance();
        OpenshiftClusterData openshiftClusterData = instance.getKafkaClusterData();
        if (openshiftClusterData != null && testMethod.isPresent() && openshiftClusterData.getCountOfWorkers() != null) {
            AtomicInteger i = new AtomicInteger(16);
            getManagedKafkaAssertionStream(openshiftClusterData, testMethod).forEach(f -> i.set(f.expectedMinKafkaInstances()));
            return Optional.of(i.intValue());
        } else {
            return Optional.empty();
        }
    }

    private static Stream<ManagedKafkaAssertion> getManagedKafkaAssertionStream(OpenshiftClusterData openshiftClusterData, Optional<Method> testMethod) {
        return Arrays.stream(testMethod.get().getAnnotationsByType(ManagedKafkaAssertion.class))
                .filter(a -> a.instanceType().equals(openshiftClusterData.getWorkerInstanceType()))
                .sorted(Comparator.comparingInt(ManagedKafkaAssertion::minWorkers))
                .collect(Collectors.toList())
                .stream();
    }
}
