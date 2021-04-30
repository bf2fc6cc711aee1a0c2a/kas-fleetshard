package org.bf2.sync;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import org.bf2.sync.informer.LocalLookup;
import org.jboss.logging.Logger;

import javax.inject.Inject;

public class KasFleetShardSync implements QuarkusApplication {

    @Inject
    Logger log;

    @Inject
    LocalLookup lookup;

    @SuppressFBWarnings(justification = "see comment below about initialization")
    @Override
    public int run(String... args) throws Exception {
        log.info("Managed Kafka sync");

        // workaround to initialize informermanager
        // if it uses start/stop, then mocking can't seem
        // to prevent that from occurring
        lookup.getLocalManagedKafkas();

        Quarkus.waitForExit();
        return 0;
    }

}
