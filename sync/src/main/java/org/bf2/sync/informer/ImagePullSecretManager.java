package org.bf2.sync.informer;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.scheduler.Scheduled;
import org.bf2.common.ImagePullSecretUtils;
import org.bf2.common.OperandUtils;
import org.bf2.sync.controlplane.ControlPlane;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class ImagePullSecretManager {

    @Inject
    Logger log;

    @Inject
    ControlPlane controlPlane;

    @Inject
    KubernetesClient client;

    private List<LocalObjectReference> imagePullSecretRefs;

    private Map<String, ObjectMeta> secretMeta = new HashMap<>();

    @PostConstruct
    void initialize() {
        /*
         * The presence of `imagePullSecrets` bound to the the kas-fleetshard-operator is the trigger
         * to propagate the secrets to new managed Kafka namespaces. The operator is assumed to be in
         * the same namespace as this sync component.
         */
        this.imagePullSecretRefs = ImagePullSecretUtils.getImagePullSecrets(client, OperandUtils.FLEETSHARD_OPERATOR_NAME);

        this.secretMeta = imagePullSecretRefs.stream()
                .map(this::secretFromReference)
                .map(Secret::getMetadata)
                .collect(Collectors.toMap(ObjectMeta::getName, meta -> meta));
    }

    public List<Secret> getImagePullSecrets() {
        return imagePullSecretRefs.stream().map(this::secretFromReference).collect(Collectors.toList());
    }

    Secret secretFromReference(LocalObjectReference ref) {
        return client.secrets().inNamespace(client.getNamespace()).withName(ref.getName()).get();
    }

    @Scheduled(every = "60s")
    public void checkSecret() {
        List<Secret> updatedSecrets = this.secretMeta.entrySet().stream()
            .map(this::updatedSecretOrNull)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (!updatedSecrets.isEmpty()) {
            controlPlane.getDesiredStates().stream()
                .map(remote -> remote.getMetadata().getNamespace())
                .forEach(ns -> {
                    try {
                        ImagePullSecretUtils.propagateSecrets(client, ns, updatedSecrets);
                    } catch (Exception e) {
                        log.warnf("Exception propagating pull secrets to namespace %s: %s", ns, e.getMessage());
                    }
                });
        }
    }

    Secret updatedSecretOrNull(Map.Entry<String, ObjectMeta> entry) {
        final String secretName = entry.getKey();
        final ObjectMeta previousMeta = entry.getValue();
        final Secret currentSecret;
        final ObjectMeta currentMeta;

        currentSecret = client.secrets().inNamespace(client.getNamespace()).withName(secretName).get();
        currentMeta = currentSecret.getMetadata();

        if (secretUpdated(previousMeta, currentMeta)) {
            log.infof("Changed detected in image pull secret: %s", secretName);
            return currentSecret;
        } else {
            log.debugf("No change in image pull secret: %s", secretName);
        }

        return null;
    }

    boolean secretUpdated(ObjectMeta previous, ObjectMeta current) {
        return !previous.getResourceVersion().equals(current.getResourceVersion())
                || !previous.getUid().equals(current.getUid());
    }

}
