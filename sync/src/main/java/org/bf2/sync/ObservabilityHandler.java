package org.bf2.sync;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.scheduler.Scheduled;

/**
 * Observability configuration, this at startup creates a configmap based on properties provided.
 * Observability operator picks this config map configures the metrics for Kafka based
 * on the content supplied. Upon successful configuration sets a annotation on ConfigMap as Status.
 * On any changes to configuration, the application restarts and configures a new/updates to configmap
 */
@ApplicationScoped
public class ObservabilityHandler {

    static final String OBSERVABILITY_CONFIGMAP_NAME = "fleetshard-observability";

    @Inject
    Logger log;

    @Inject
    protected KubernetesClient client;

    @ConfigProperty(name = "observability.access_token")
    String accessToken;

    @ConfigProperty(name = "observability.channel")
    String channel;

    @ConfigProperty(name = "observability.repository")
    String repository;

    @Scheduled(every = "60s")
    void loop() {
        // if in case onStart fails or anyone deletes the configMap this makes sure the configMap created again
        if (observabilityConfigMap().get() == null) {
            observabilityConfigMap().createOrReplace(createObservabilityConfigMap());
            log.infof("ConfigMap %s created for the Observability Operator", OBSERVABILITY_CONFIGMAP_NAME);
        }
    }

    Resource<ConfigMap> observabilityConfigMap() {
        return this.client.configMaps().inNamespace(this.client.getNamespace()).withName(OBSERVABILITY_CONFIGMAP_NAME);
    }

    ConfigMap createObservabilityConfigMap() {
        return createObservabilityConfigMapBuilder().build();
    }

    ConfigMapBuilder createObservabilityConfigMapBuilder() {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withNamespace(this.client.getNamespace())
                    .withName(OBSERVABILITY_CONFIGMAP_NAME)
                    .addToLabels("configures", "observability-operator")
                    .addToLabels("app.kubernetes.io/managed-by", "kas-fleetshard-operator")
                .endMetadata()
                .addToData("access_token",this.accessToken)
                .addToData("channel", this.channel)
                .addToData("repository", this.repository);
    }

    public boolean isObservabilityRunning() {
        ConfigMap cm = observabilityConfigMap().get();
        if (cm != null) {
            String status = cm.getMetadata().getAnnotations().get("observability-operator/status");
            if (status != null && status.equalsIgnoreCase("accepted")) {
                return true;
            }
        }
        return false;
    }
}
