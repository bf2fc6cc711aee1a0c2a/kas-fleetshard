package org.bf2.operator.clients.canary;

import io.quarkus.arc.DefaultBean;
import org.bf2.operator.operands.AbstractCanary;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.net.URI;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
@DefaultBean
public class CanaryStatusService {

    @Inject
    ManagedExecutor executor;

    public Status get(ManagedKafka managedKafka) throws Exception {
        String namespace = AbstractCanary.canaryNamespace(managedKafka);
        String canaryName = AbstractCanary.canaryName(managedKafka);

        try (CanaryService canaryService = createClient(namespace, canaryName)) {
            return canaryService.getStatus();
        }
    }

    CanaryService createClient(String namespace, String canaryName) {
        return RestClientBuilder.newBuilder()
                .executorService(executor)
                .baseUri(URI.create("http://" + canaryName + "." + namespace + ":8080"))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(CanaryService.class);
    }
}
