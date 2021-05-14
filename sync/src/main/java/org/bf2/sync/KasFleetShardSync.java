package org.bf2.sync;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import org.jboss.logging.Logger;

import javax.inject.Inject;

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
