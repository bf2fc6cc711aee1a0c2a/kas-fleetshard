package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.common.OperandUtils;
import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class ObservabilityManager {
    static final String OBSERVABILITY_REPOSITORY = "repository";
    static final String OBSERVABILITY_CHANNEL = "channel";
    static final String OBSERVABILITY_ACCESS_TOKEN = "access_token";
    static final String OBSERVABILITY_TAG = "tag";
    public static final String OBSERVABILITY_SECRET_NAME = "fleetshard-observability";
    public static final String OBSERVABILITY_OPERATOR_STATUS = "observability-operator/status";
    public static final String ACCEPTED = "accepted";

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
        data.put(OBSERVABILITY_ACCESS_TOKEN, encoder.encodeToString(observability.getAccessToken().getBytes(StandardCharsets.UTF_8)));
        data.put(OBSERVABILITY_REPOSITORY, encoder.encodeToString(observability.getRepository().getBytes(StandardCharsets.UTF_8)));
        if (observability.getChannel() != null) {
            data.put(OBSERVABILITY_CHANNEL, encoder.encodeToString(observability.getChannel().getBytes(StandardCharsets.UTF_8)));
        }
        if (observability.getTag() != null) {
            data.put(OBSERVABILITY_TAG, encoder.encodeToString(observability.getTag().getBytes(StandardCharsets.UTF_8)));
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
        Map<String, String> annotations = Objects.requireNonNullElse(cm.getMetadata().getAnnotations(), Collections.emptyMap());
        String status = annotations.get(OBSERVABILITY_OPERATOR_STATUS);
        return ACCEPTED.equalsIgnoreCase(status);
    }

    Resource<Secret> observabilitySecretResource() {
        return this.client.secrets().inNamespace(this.client.getNamespace()).withName(OBSERVABILITY_SECRET_NAME);
    }

    Secret cachedObservabilitySecret() {
        return informerManager.getLocalSecret(this.client.getNamespace(),
                ObservabilityManager.OBSERVABILITY_SECRET_NAME);
    }

    public void createOrUpdateObservabilitySecret(ObservabilityConfiguration observability, HasMetadata owner) {
        Secret secret = createObservabilitySecret(this.client.getNamespace(), observability);
        OperandUtils.setAsOwner(owner, secret);
        if (cachedObservabilitySecret() == null) {
            observabilitySecretResource().create(secret);
        } else {
            observabilitySecretResource().edit(s -> {
                s.setData(secret.getData());
                return s;
            });
        }
    }

    public boolean isObservabilityRunning() {
        Secret secret = cachedObservabilitySecret();
        return secret != null && isObservabilityStatusAccepted(secret);
    }
}
