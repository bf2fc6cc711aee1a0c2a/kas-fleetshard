package org.bf2.operator;

import javax.inject.Inject;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.Operator;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class AgentOperator implements QuarkusApplication {

    @Inject
    private KubernetesClient client;

    @Inject
    private Operator operator;

    @Inject
    private ManagedKafkaController managedKafkaController;

    @Override
    public int run(String... args) throws Exception {
        // TODO Auto-generated method stub
        System.out.println("Agent operator");
        Quarkus.waitForExit();
        return 0;
    }
}
