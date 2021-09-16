package org.bf2.operator;

import io.javaoperatorsdk.operator.Operator;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import javax.inject.Inject;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class KasFleetShardOperator implements QuarkusApplication {

    @Inject
    Logger log;

    @Inject
    Operator operator;

    @Override
    public int run(String... args) throws Exception {
        log.info("kas fleetshard operator");

        printConfiguration();

        operator.start();
        Quarkus.waitForExit();
        return 0;
    }

    private void printConfiguration() {
        List<String> config = StreamSupport.stream(ConfigProvider.getConfig().getPropertyNames().spliterator(), false)
            .sorted()
            .collect(Collectors.toList());
        config.forEach( e -> {
            try {
                String value = ConfigProvider.getConfig().getValue(e, String.class);
                log.infof("%s=%s", e, value);
            } catch (NoSuchElementException ex) {
                // ignore as some property values are not available
            }
        });
    }
}
