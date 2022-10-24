package org.bf2.operator.clients.canary;

import io.quarkus.arc.DefaultBean;
import org.bf2.operator.operands.AbstractCanary;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import javax.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
@DefaultBean
public class CanaryStatusService {

    public Status get(ManagedKafka managedKafka) throws Exception {
        CanaryService canaryService = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://" + AbstractCanary.canaryName(managedKafka) + "." + managedKafka.getMetadata().getNamespace() + ":8080"))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(CanaryService.class);

        return canaryService.getStatus();
    }
}
