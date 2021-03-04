package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;

import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;

public class ObservabilityClient {
    static final String OBSERVABILITY_CONFIGMAP_NAME = "fleetshard-observability";

    static Resource<ConfigMap> observabilityConfigMap(KubernetesClient client, String namespace) {
        return client.configMaps().inNamespace(namespace).withName(OBSERVABILITY_CONFIGMAP_NAME);
    }

    public static void createOrUpdateObservabilityConfigMap(KubernetesClient client, String namespace,
            ObservabilityConfiguration observability) {
        ConfigMap configMap = createObservabilityConfigMap(namespace, observability);
        if (observabilityConfigMap(client, namespace).get() == null) {
            observabilityConfigMap(client, namespace).createOrReplace(configMap);
        } else {
            observabilityConfigMap(client, namespace).patch(configMap);
        }
    }

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

    public static boolean isObservabilityRunning(KubernetesClient client, String namespace) {
        ConfigMap cm = observabilityConfigMap(client, namespace).get();
        if (cm != null) {
            String status = cm.getMetadata().getAnnotations().get("observability-operator/status");
            if (status != null && status.equalsIgnoreCase("accepted")) {
                return true;
            }
        }
        return false;
    }
}
