package org.bf2.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;

public class AgentSync implements QuarkusApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentSync.class);

    @Override
    public int run(String... args) throws Exception {
        log.info("Managed Kafka agent sync");

        Quarkus.waitForExit();
        return 0;
    }

}
