package org.bf2.operator.eventhandlers;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.bf2.operator.InformerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
/**
 * Observabilty configuration, this at startup time looks for addon based secret, if it find it then creates
 * a configmap based on it. Observability operator picks this config map configures the metrics for Kafka based
 * on the content supplied. Upon successful configuration sets a annotation on ConfigMap as Status. This class also
 * monitors any changes to Secret file and carries out any updates to ConfigMap.
 */
public class ObservabilityHandler implements ResourceEventHandler<Secret> {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityHandler.class);
    static final String OBSERVABILITY_CONFIGMAP_NAME = "fleetshard-observability";

    @Inject
    protected KubernetesClient client;

    void onStart(@Observes StartupEvent ev) {
        Secret secret = this.client.secrets().inNamespace(this.client.getNamespace()).withName(InformerManager.SECRET_NAME).get();
        if (secret != null) {
            findConfigMap().createOrReplace(createConfigMap(secret));
            log.info("Observability configuration for fleetshard operator created");
        } else {
            log.error("Observability configuration for fleetshard operator con not be created because secret does not exist");
        }
    }

    @Override
    public void onAdd(Secret obj) {
        findConfigMap().createOrReplace(createConfigMap(obj));
        log.info("Observability configuration for fleetshard operator created");
    }

    @Override
    public void onUpdate(Secret oldObj, Secret newObj) {
        if (!oldObj.getMetadata().getResourceVersion().equals(newObj.getMetadata().getResourceVersion())) {
            return;
        }
        findConfigMap().createOrReplace(createConfigMap(newObj));
        log.info("Observability configuration for fleetshard operator updated");
    }

    @Override
    public void onDelete(Secret obj, boolean deletedFinalStateUnknown) {
        if(findConfigMap().delete()) {
            log.info("Observability configuration for fleetshard operator deleted");
        }
    }

    Resource<ConfigMap> findConfigMap(){
        return this.client.configMaps().inNamespace(this.client.getNamespace()).withName(OBSERVABILITY_CONFIGMAP_NAME);
    }

    ConfigMap createConfigMap(Secret secret) {
        return createConfigMapBuilder(secret).build();
    }

    ConfigMapBuilder createConfigMapBuilder(Secret secret) {
        //TODO: property names from secret needs to be confirmed with control plane
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withNamespace(secret.getMetadata().getNamespace())
                    .withName(OBSERVABILITY_CONFIGMAP_NAME)
                    .addToLabels("configures", "observability-operator")
                .endMetadata()
                .addToData("access_token", secret.getData().get("observability.access_token"))
                .addToData("channel", secret.getData().get("observability.channel"))
                .addToData("repository", secret.getData().get("observability.repository"));
    }

    public boolean isObservabilityRunning() {
        ConfigMap cm = findConfigMap().get();
        if (cm != null) {
            String status = cm.getMetadata().getAnnotations().get("observability-operator/status");
            if (status != null && status.equalsIgnoreCase("accepted")) {
                return true;
            }
        }
        return false;
    }
}
