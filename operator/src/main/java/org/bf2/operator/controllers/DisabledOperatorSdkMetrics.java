package org.bf2.operator.controllers;

import io.javaoperatorsdk.operator.api.monitoring.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.quarkus.arc.properties.UnlessBuildProperty;

import javax.inject.Singleton;

/**
 * Implementation of the java-operator-sdk {@link Metrics} interface that does
 * will not add the SDK's reconcile/event metrics to the Micrometer registry. This
 * avoids a problem where the SDK's metrics accumulate and result in an OOM situation
 * during scraping of the `/metrics` end-point.
 */
@Singleton
@UnlessBuildProperty(name = "managedkafka.operator-sdk.default-metrics", stringValue = "true", enableIfMissing = true)
public class DisabledOperatorSdkMetrics implements Metrics, MeterBinder {

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        // Do nothing with the registry
    }
}
