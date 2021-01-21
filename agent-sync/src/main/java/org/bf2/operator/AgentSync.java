package org.bf2.operator;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;

public class AgentSync implements QuarkusApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentSync.class);

    @Inject
    private KubernetesClient localClient;

    //we can't inject this as the quarkus logic supports a singleton client
    private KubernetesClient remoteClient;

    @Override
    public int run(String... args) throws Exception {
        log.info("Managed Kafka agent sync");
        
        Quarkus.waitForExit();
        return 0;
    }
}
