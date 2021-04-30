package org.bf2.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.Operator;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import org.bf2.operator.controllers.ManagedKafkaController;
import org.jboss.logging.Logger;

import javax.inject.Inject;

public class KasFleetShardOperator implements QuarkusApplication {

    @Inject
    Logger log;

    @Inject
    KubernetesClient client;

    @Inject
    Operator operator;

    @Inject
    ManagedKafkaController managedKafkaController;

    @Override
    public int run(String... args) throws Exception {
        log.info("kas fleetshard operator");
        Quarkus.waitForExit();
        return 0;
    }
}
