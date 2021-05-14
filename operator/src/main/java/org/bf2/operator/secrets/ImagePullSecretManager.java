package org.bf2.operator.secrets;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.scheduler.Scheduled;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class ImagePullSecretManager {

    @Inject
    Logger log;

    @Inject
    KubernetesClient client;

    @Inject
    ManagedKafkaResourceClient managedKafkaResourceClient;

    private List<LocalObjectReference> imagePullSecretRefs;

    private volatile Map<String, Secret> secrets;

    private static String getSecretName(ManagedKafka managedKafka, String name) {
        return managedKafka.getMetadata().getName() + "-pull-" + name;
    }

    static void propagateSecrets(KubernetesClient client, ManagedKafka mk, Collection<Secret> secrets) {
        secrets.stream()
                .forEach(secret -> {
                    Secret s = new SecretBuilder(secret)
                        .withNewMetadata()
                            .withNamespace(mk.getMetadata().getNamespace())
                            .withName(getSecretName(mk, secret.getMetadata().getName()))
                            .withLabels(OperandUtils.getDefaultLabels())
                        .endMetadata()
                        .build();
                    OperandUtils.setAsOwner(mk, s);
                    client.secrets().inNamespace(mk.getMetadata().getNamespace()).createOrReplace(s);
                });
    }

    static List<LocalObjectReference> getImagePullSecrets(KubernetesClient client, String deploymentName) {
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

        return secrets;
    }

    @PostConstruct
    void initialize() {
        /*
         * The presence of `imagePullSecrets` bound to the the kas-fleetshard-operator is the trigger
         * to propagate the secrets to new managed Kafka namespaces. The operator is assumed to be in
         * the same namespace as this component.
         */
        this.imagePullSecretRefs = getImagePullSecrets(client, OperandUtils.FLEETSHARD_OPERATOR_NAME);
        this.secrets = null;
        if (log.isInfoEnabled()) {
            if (imagePullSecretRefs.isEmpty()) {
                log.infof("No `imagePullSecrets` defined for %s/%s", client.getNamespace(), OperandUtils.FLEETSHARD_OPERATOR_NAME);
            } else {
                log.infof("Using `imagePullSecrets` from %s/%s: [%s]", client.getNamespace(), OperandUtils.FLEETSHARD_OPERATOR_NAME,
                        imagePullSecretRefs.stream().map(LocalObjectReference::getName).collect(Collectors.joining(", ")));
            }
        }
    }

    Secret secretFromReference(LocalObjectReference ref) {
        return client.secrets().inNamespace(client.getNamespace()).withName(ref.getName()).get();
    }

    @Scheduled(every = "60s")
    void checkSecret() {
        Map<String, Secret> newSecretMeta = imagePullSecretRefs.stream()
                .map(this::secretFromReference)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(s->s.getMetadata().getName(), Function.identity()));

        Collection<Secret> updatedSecrets;
        if (this.secrets == null) {
            // if the secrets have changed in between pod stop and now, we won't detect it
            // so propagate just in case
            updatedSecrets = newSecretMeta.values();
        } else {
            updatedSecrets = newSecretMeta.entrySet().stream()
                    .map(this::updatedSecretOrNull)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        this.secrets = newSecretMeta;

        if (!updatedSecrets.isEmpty()) {
            if (log.isInfoEnabled()) {
                log.infof("Propagating secrets [%s]",
                        updatedSecrets.stream().map(s -> s.getMetadata().getName()).collect(Collectors.joining(", ")));
            }
            managedKafkaResourceClient.list().stream()
                .forEach(mk -> {
                    try {
                        propagateSecrets(client, mk, updatedSecrets);
                    } catch (Exception e) {
                        log.warnf("Exception propagating pull secrets to namespace %s: %s", mk, e.getMessage());
                    }
                });
        }
    }

    public List<LocalObjectReference> getOperatorImagePullSecrets(ManagedKafka managedKafka) {
        return this.imagePullSecretRefs.stream()
                .map(s -> new LocalObjectReferenceBuilder(s).withName(getSecretName(managedKafka, s.getName())).build())
                .collect(Collectors.toList());
    }

    Secret updatedSecretOrNull(Map.Entry<String, Secret> entry) {
        final String secretName = entry.getKey();
        Secret secret = this.secrets.get(secretName);
        final ObjectMeta previousMeta = secret != null ? secret.getMetadata() : null;
        final Secret currentSecret = entry.getValue();
        final ObjectMeta currentMeta = entry.getValue().getMetadata();

        if (secretUpdated(previousMeta, currentMeta)) {
            log.infof("Changed detected in image pull secret: %s", secretName);
            return currentSecret;
        } else {
            log.tracef("No change in image pull secret: %s", secretName);
        }

        return null;
    }

    boolean secretUpdated(ObjectMeta previous, ObjectMeta current) {
        return previous == null || !previous.getResourceVersion().equals(current.getResourceVersion())
                || !previous.getUid().equals(current.getUid());
    }

    public void propagateSecrets(ManagedKafka managedKafka) {
        if (secrets != null && !secrets.isEmpty()) {
            propagateSecrets(client, managedKafka, secrets.values());
        }
    }

    public void deleteSecrets(ManagedKafka managedKafka) {
        imagePullSecretRefs.stream()
                .forEach(s -> client.secrets()
                        .inNamespace(managedKafka.getMetadata().getNamespace())
                        .withName(getSecretName(managedKafka, s.getName()))
                        .delete());
    }

}
