package org.bf2.operator;

import javax.inject.Inject;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.Operator;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.bf2.operator.controllers.ManagedKafkaController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusMain
public class AgentOperator implements QuarkusApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentOperator.class);

    @Inject
    private KubernetesClient client;

    @Inject
    private Operator operator;

    @Inject
    private ManagedKafkaController managedKafkaController;

    @Override
    public int run(String... args) throws Exception {
        log.info("Managed Kafka agent operator");
        Quarkus.waitForExit();
        return 0;
    }
}
