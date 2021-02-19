package org.bf2.operator.eventhandlers;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import java.util.Base64;

import org.bf2.operator.InformerManager;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
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

    static final String OBSERVABILITY_CONFIGMAP_NAME = "fleetshard-observability";

    @Inject
    Logger log;

    @Inject
    protected KubernetesClient client;

    void onStart(@Observes StartupEvent ev) {
        Secret secret = this.client.secrets().inNamespace(this.client.getNamespace()).withName(InformerManager.SECRET_NAME).get();
        if (secret != null) {
            observabilityConfigMap().createOrReplace(createObservabilityConfigMap(secret));
            log.info("Observability configuration for fleetshard operator created");
        } else {
            log.warn("Observability configuration for fleetshard operator con not be created because secret does not exist");
        }
    }

    @Override
    public void onAdd(Secret obj) {
        if (isAddOnFleetShardSecret(obj)) {
            observabilityConfigMap().createOrReplace(createObservabilityConfigMap(obj));
            log.info("Observability configuration for fleetshard operator created");
        }
    }

    @Override
    public void onUpdate(Secret oldObj, Secret newObj) {
        if (isAddOnFleetShardSecret(oldObj)
                && !oldObj.getMetadata().getResourceVersion().equals(newObj.getMetadata().getResourceVersion())) {
            observabilityConfigMap().createOrReplace(createObservabilityConfigMap(newObj));
            log.info("Observability configuration for fleetshard operator updated");
        }
    }

    @Override
    public void onDelete(Secret obj, boolean deletedFinalStateUnknown) {
        if (isAddOnFleetShardSecret(obj)) {
            if(observabilityConfigMap().delete()) {
                log.info("Observability configuration for fleetshard operator deleted");
            }
        }
    }

    boolean isAddOnFleetShardSecret(Secret obj) {
        return obj.getMetadata().getName().equals(InformerManager.SECRET_NAME);
    }

    Resource<ConfigMap> observabilityConfigMap(){
        return this.client.configMaps().inNamespace(this.client.getNamespace()).withName(OBSERVABILITY_CONFIGMAP_NAME);
    }

    ConfigMap createObservabilityConfigMap(Secret secret) {
        return createObservabilityConfigMapBuilder(secret).build();
    }

    ConfigMapBuilder createObservabilityConfigMapBuilder(Secret secret) {
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(secret.getApiVersion())
                .withKind(secret.getKind())
                .withName(secret.getMetadata().getName())
                .withUid(secret.getMetadata().getUid())
                .build();

        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withNamespace(secret.getMetadata().getNamespace())
                    .withName(OBSERVABILITY_CONFIGMAP_NAME)
                    .addToLabels("configures", "observability-operator")
                    .addToLabels("app.kubernetes.io/managed-by", "kas-fleetshard-operator")
                    .withOwnerReferences(ownerReference)
                .endMetadata()
                .addToData("access_token", getSecretData(secret,"observability.access_token"))
                .addToData("channel", getSecretData(secret,"observability.channel"))
                .addToData("repository", getSecretData(secret,"observability.repository"));
    }

    static String getSecretData(Secret secret, String propertyName) {
        return new String(Base64.getDecoder().decode(secret.getData().get(propertyName)));
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
