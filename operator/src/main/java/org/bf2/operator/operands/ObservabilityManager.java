package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;

import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class ObservabilityManager {
    static final String OBSERVABILITY_CONFIGMAP_NAME = "fleetshard-observability";

    @Inject
    KubernetesClient client;

    static ConfigMap createObservabilityConfigMap(String namespace, ObservabilityConfiguration observability) {
        return createObservabilityConfigMapBuilder(namespace, observability).build();
    }

    static ConfigMapBuilder createObservabilityConfigMapBuilder(String namespace, ObservabilityConfiguration observability) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withNamespace(namespace)
                    .withName(OBSERVABILITY_CONFIGMAP_NAME)
                    .addToLabels("configures", "observability-operator")
                    .addToLabels("app.kubernetes.io/managed-by", "kas-fleetshard-operator")
                .endMetadata()
                .addToData("access_token", observability.getAccessToken())
                .addToData("channel", observability.getChannel())
                .addToData("repository", observability.getRepository());
    }

    Resource<ConfigMap> observabilityConfigMap() {
        return this.client.configMaps().inNamespace(this.client.getNamespace()).withName(OBSERVABILITY_CONFIGMAP_NAME);
    }

    public void createOrUpdateObservabilityConfigMap(ObservabilityConfiguration observability) {
        ConfigMap configMap = createObservabilityConfigMap(this.client.getNamespace(), observability);
        if (observabilityConfigMap().get() == null) {
            observabilityConfigMap().createOrReplace(configMap);
        } else {
            observabilityConfigMap().patch(configMap);
        }
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
