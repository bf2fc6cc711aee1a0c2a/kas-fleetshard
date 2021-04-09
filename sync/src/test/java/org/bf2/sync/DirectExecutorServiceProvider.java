package org.bf2.sync;

import com.google.common.util.concurrent.MoreExecutors;
import io.quarkus.test.Mock;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

import java.util.concurrent.ExecutorService;

@Mock
@ApplicationScoped
public class DirectExecutorServiceProvider {

    ExecutorService direct = MoreExecutors.newDirectExecutorService();

    @Produces
    public ExecutorService executorService() {
        return direct;
    }

}
