package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;

import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class ObservabilityManager {
    static final String OBSERVABILITY_REPOSITORY = "repository";
    static final String OBSERVABILITY_CHANNEL = "channel";
    static final String OBSERVABILITY_ACCESS_TOKEN = "access_token";
    public static final String OBSERVABILITY_CONFIGMAP_NAME = "fleetshard-observability";

    @Inject
    KubernetesClient client;

    @Inject
    InformerManager informerManager;

    static ConfigMap createObservabilityConfigMap(String namespace, ObservabilityConfiguration observability) {
        return createObservabilityConfigMapBuilder(namespace, observability).build();
    }

    static ConfigMapBuilder createObservabilityConfigMapBuilder(String namespace, ObservabilityConfiguration observability) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withNamespace(namespace)
                    .withName(OBSERVABILITY_CONFIGMAP_NAME)
                    .addToLabels("configures", "observability-operator")
                    .addToLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .addToData(OBSERVABILITY_ACCESS_TOKEN, observability.getAccessToken())
                .addToData(OBSERVABILITY_CHANNEL, observability.getChannel())
                .addToData(OBSERVABILITY_REPOSITORY, observability.getRepository());
    }

    static boolean isObservabilityStatusAccepted(ConfigMap cm) {
        String status = cm.getMetadata().getAnnotations().get("observability-operator/status");
        if (status != null && status.equalsIgnoreCase("accepted")) {
            return true;
        }
        return false;
    }

    Resource<ConfigMap> observabilityConfigMapResource() {
        return this.client.configMaps().inNamespace(this.client.getNamespace()).withName(OBSERVABILITY_CONFIGMAP_NAME);
    }

    private ConfigMap cachedObservabilityConfigMap() {
        return informerManager.getLocalConfigMap(this.client.getNamespace(),
                ObservabilityManager.OBSERVABILITY_CONFIGMAP_NAME);
    }

    public void createOrUpdateObservabilityConfigMap(ObservabilityConfiguration observability) {
        ConfigMap configMap = createObservabilityConfigMap(this.client.getNamespace(), observability);
        if (cachedObservabilityConfigMap() == null) {
            observabilityConfigMapResource().createOrReplace(configMap);
        } else {
            observabilityConfigMapResource().patch(configMap);
        }
    }

    public boolean isObservabilityRunning() {
        ConfigMap cm = cachedObservabilityConfigMap();
        if (cm != null) {
            return isObservabilityStatusAccepted(cm);
        }
        return false;
    }
}
