package org.bf2.operator;

import io.javaoperatorsdk.operator.Operator;
import io.quarkiverse.operatorsdk.bundle.runtime.CSVMetadata;
import io.quarkiverse.operatorsdk.bundle.runtime.CSVMetadata.InstallMode;
import io.quarkiverse.operatorsdk.bundle.runtime.CSVMetadata.Provider;
import io.quarkiverse.operatorsdk.bundle.runtime.SharedCSVMetadata;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import javax.inject.Inject;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@CSVMetadata(
        description = "Operator That Manages Kafka Instances",
        displayName = "KaaS Fleetshard Operator",
        provider = @Provider(
                name = "bf2 community",
                url = "https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard"),
        minKubeVersion = "1.21.0",
        keywords = { "managed", "kafka" },
        maturity = "alpha",
        installModes = {
                @InstallMode(type = "OwnNamespace", supported = false),
                @InstallMode(type = "SingleNamespace", supported = false),
                @InstallMode(type = "MultiNamespace", supported = false),
                @InstallMode(type = "AllNamespaces", supported = true)
        }
)
public class KasFleetShardOperator implements QuarkusApplication, SharedCSVMetadata {

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
