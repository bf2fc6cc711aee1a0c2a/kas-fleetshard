package org.bf2.common;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ImagePullSecretUtils {

    private static final Logger log = Logger.getLogger(ImagePullSecretUtils.class);

    private ImagePullSecretUtils() {
    }

    public static List<LocalObjectReference> getImagePullSecrets(KubernetesClient client, String deploymentName) {
        final String namespace = client.getNamespace();

        Deployment deployment = client.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(deploymentName)
                .get();

        List<LocalObjectReference> secrets = deployment == null ? Collections.emptyList() :
                deployment.getSpec()
                .getTemplate()
                .getSpec()
                .getImagePullSecrets();

        if (log.isInfoEnabled()) {
            if (secrets.isEmpty()) {
                log.infof("No `imagePullSecrets` defined for %s/%s", namespace, deploymentName);
            } else {
                log.infof("Using `imagePullSecrets` from %s/%s: [%s]", namespace, deploymentName,
                        secrets.stream().map(LocalObjectReference::getName).collect(Collectors.joining(", ")));
            }
        }

        return secrets;
    }

    public static void propagateSecrets(KubernetesClient client, String remoteNamespace, List<Secret> secrets) {
        if (secrets.isEmpty()) {
            return;
        }

        Secret[] propagated = secrets.stream()
                .map(secret -> new SecretBuilder(secret)
                    .withNewMetadata()
                        .withNamespace(remoteNamespace)
                        .withName(secret.getMetadata().getName())
                    .endMetadata()
                    .build())
                .toArray(Secret[]::new);

        if (log.isInfoEnabled()) {
            log.infof("Propagating secrets to %s namespace: [%s]", remoteNamespace,
                    secrets.stream().map(s -> s.getMetadata().getName()).collect(Collectors.joining(", ")));
        }

        client.secrets().inNamespace(remoteNamespace).createOrReplace(propagated);
    }
}
