package org.bf2.sync;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;

@ApplicationScoped
public class ExecutorServiceProvider implements UncaughtExceptionHandler {

    private static final String NAME = "sync.threadpool";

    @Inject
    Logger log;

    private MeterRegistry meterRegistry;

    // the number of threads should be less than the size of the rest connection pool (50 by default)
    ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 10, 1, TimeUnit.MINUTES, new ArrayBlockingQueue<>(10000),
            new ThreadFactory() {
                ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = defaultThreadFactory.newThread(r);
                    t.setUncaughtExceptionHandler(ExecutorServiceProvider.this);
                    return t;
                }
            },
            new ThreadPoolExecutor.DiscardOldestPolicy() {
                @Override
                public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                    log.warn("Queue is full - purging an old task");
                    super.rejectedExecution(r, e);
                }

            });

    public ExecutorServiceProvider(MeterRegistry meterRegistry) {
        ExecutorServiceMetrics.monitor(meterRegistry, executor, NAME);
        this.meterRegistry = meterRegistry;
    }

    @Produces
    public ExecutorService executorService() {
        return executor;
    }

    @PreDestroy
    void shutdown() {
        // we don't need to be more graceful than this as any action will be retried
        executor.shutdownNow();
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        Counter.Builder builder = Counter.builder("executor.failed.tasks")
                .tag("name", NAME)
                .tag("exception", getExceptionTag(e))
                .tag("result", "failure")
                .description("The number of tasks that have failed");
        builder.register(meterRegistry).increment();
    }

    private String getExceptionTag(Throwable e) {
        if (e.getCause() != null) {
            return e.getCause().getClass().getSimpleName();
        }
        return e.getClass().getSimpleName();
    }

}
