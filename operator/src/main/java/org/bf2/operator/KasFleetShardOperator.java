package org.bf2.operator;

import io.javaoperatorsdk.operator.Operator;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import org.jboss.logging.Logger;

import javax.inject.Inject;

public class KasFleetShardOperator implements QuarkusApplication {

    @Inject
    Logger log;

    @Inject
    Operator operator;

    @Override
    public int run(String... args) throws Exception {
        log.info("kas fleetshard operator");

        Quarkus.waitForExit();
        return 0;
    }
}
