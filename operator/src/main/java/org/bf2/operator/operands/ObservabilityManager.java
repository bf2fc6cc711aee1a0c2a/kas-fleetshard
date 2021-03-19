package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;

import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class ObservabilityManager {
    static final String OBSERVABILITY_REPOSITORY = "repository";
    static final String OBSERVABILITY_CHANNEL = "channel";
    static final String OBSERVABILITY_ACCESS_TOKEN = "access_token";
    static final String OBSERVABILITY_TAG = "tag";
    public static final String OBSERVABILITY_SECRET_NAME = "fleetshard-observability";

    @Inject
    KubernetesClient client;

    @Inject
    InformerManager informerManager;

    static Base64.Encoder encoder = Base64.getEncoder();

    static Secret createObservabilitySecret(String namespace, ObservabilityConfiguration observability) {
        return createObservabilitySecretBuilder(namespace, observability).build();
    }

    static SecretBuilder createObservabilitySecretBuilder(String namespace, ObservabilityConfiguration observability) {
        Map<String, String> data = new HashMap<>(2);
        data.put(OBSERVABILITY_ACCESS_TOKEN, encoder.encodeToString(observability.getAccessToken().getBytes()));
        data.put(OBSERVABILITY_REPOSITORY, encoder.encodeToString(observability.getRepository().getBytes()));
        if (observability.getChannel() != null) {
            data.put(OBSERVABILITY_CHANNEL, encoder.encodeToString(observability.getChannel().getBytes()));
        }
        if (observability.getTag() != null) {
            data.put(OBSERVABILITY_TAG, encoder.encodeToString(observability.getTag().getBytes()));
        }
        return new SecretBuilder()
                .withNewMetadata()
                    .withNamespace(namespace)
                    .withName(OBSERVABILITY_SECRET_NAME)
                    .addToLabels("configures", "observability-operator")
                    .addToLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .addToData(data);
    }

    static boolean isObservabilityStatusAccepted(Secret cm) {
        String status = cm.getMetadata().getAnnotations().get("observability-operator/status");
        if (status != null && status.equalsIgnoreCase("accepted")) {
            return true;
        }
        return false;
    }

    Resource<Secret> observabilitySecretResource() {
        return this.client.secrets().inNamespace(this.client.getNamespace()).withName(OBSERVABILITY_SECRET_NAME);
    }

    private Secret cachedObservabilitySecret() {
        return informerManager.getLocalSecret(this.client.getNamespace(),
                ObservabilityManager.OBSERVABILITY_SECRET_NAME);
    }

    public void createOrUpdateObservabilitySecret(ObservabilityConfiguration observability) {
        Secret secret = createObservabilitySecret(this.client.getNamespace(), observability);
        if (cachedObservabilitySecret() == null) {
            observabilitySecretResource().createOrReplace(secret);
        } else {
            observabilitySecretResource().patch(secret);
        }
    }

    public boolean isObservabilityRunning() {
        Secret secret = cachedObservabilitySecret();
        if (secret != null) {
            return isObservabilityStatusAccepted(secret);
        }
        return false;
    }
}
