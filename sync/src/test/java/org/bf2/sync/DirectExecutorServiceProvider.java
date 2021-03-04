package org.bf2.sync;

import java.util.concurrent.ExecutorService;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

import com.google.common.util.concurrent.MoreExecutors;

import io.quarkus.test.Mock;

@Mock
@ApplicationScoped
public class DirectExecutorServiceProvider {

    ExecutorService direct = MoreExecutors.newDirectExecutorService();

    @Produces
    public ExecutorService executorService() {
        return direct;
    }

}
