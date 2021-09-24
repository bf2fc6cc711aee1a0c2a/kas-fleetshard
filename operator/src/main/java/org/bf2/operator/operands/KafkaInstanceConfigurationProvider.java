package org.bf2.operator.operands;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.runtime.Startup;
import org.eclipse.microprofile.config.ConfigProvider;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

import java.util.Map;

@Startup
@ApplicationScoped
public class KafkaInstanceConfigurationProvider {

    private KafkaInstanceConfiguration config;

    @PostConstruct
    void init() {
        // workaround for not using ConfigProperties.  Get the full property set, look them up in the config then inject those values
        Map<String, String> values = new KafkaInstanceConfiguration().toMap(true);
        values.keySet().forEach(n -> ConfigProvider.getConfig().getOptionalValue(n, String.class).ifPresent(v -> values.put(n, v)));
        config = Serialization.jsonMapper().convertValue(values, KafkaInstanceConfiguration.class);
    }

    @Produces
    public KafkaInstanceConfiguration kafkaInstanceConfiguration() {
        return config;
    }

}
