package org.bf2.sync;

import javax.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;

public class KasFleetShardSync implements QuarkusApplication {

    @Inject
    Logger log;

    @Override
    public int run(String... args) throws Exception {
        log.info("Managed Kafka sync");

        Quarkus.waitForExit();
        return 0;
    }

}
