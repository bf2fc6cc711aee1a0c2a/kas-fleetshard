package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.model.KafkaResources;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuth;
import org.bf2.operator.resources.v1alpha1.SecretKeySelector;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;
import org.bf2.operator.resources.v1alpha1.TlsKeyPair;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class SecuritySecretManager {

    public static final String ANNOTATION_SECRET_DEP_DIGEST = "managedkafka.bf2.org/secret-dependency-digest";
    public static final String SASL_PRINCIPAL ="sasl.principal";
    public static final String SASL_PASSWORD ="sasl.password";

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    InformerManager informerManager;

    interface SecretSource {
        Secret apply(ManagedKafka managedKafka, Secret current, Secret masterSecret);
    }

    public static boolean isKafkaAuthenticationEnabled(ManagedKafka managedKafka) {
        return (managedKafka.getSpec().getOauth() != null);
    }

    public static boolean isKafkaExternalCertificateEnabled(ManagedKafka managedKafka) {
        return managedKafka.getSpec().getEndpoint().getTls() != null;
    }

    public static boolean isCanaryServiceAccountPresent(ManagedKafka managedKafka){
        return managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary)
            .map(sa -> Stream.of(sa.getPrincipalRef(), sa.getPasswordRef()).allMatch(Objects::nonNull)
                    || Stream.of(sa.getPrincipal(), sa.getPassword()).allMatch(Objects::nonNull))
            .orElse(false);
    }

    public static String masterSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-master-secret";
    }

    public static String kafkaTlsSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-tls-secret";
    }

    public static String ssoClientSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-sso-secret";
    }

    public static String ssoTlsSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-sso-cert";
    }

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    public static String strimziClusterCaCertSecret(ManagedKafka managedKafka) {
        return KafkaResources.clusterCaCertificateSecretName(managedKafka.getMetadata().getName());
    }
    public static String canarySaslSecretName(ManagedKafka managedKafka){
        return managedKafka.getMetadata().getName() + "-canary-sasl-secret";
    }

    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = true;

        if (isKafkaExternalCertificateEnabled(managedKafka)) {
            isDeleted = cachedSecret(managedKafka, kafkaTlsSecretName(managedKafka)) == null;
        }

        if (isKafkaAuthenticationEnabled(managedKafka)) {
            isDeleted = isDeleted && cachedSecret(managedKafka, ssoClientSecretName(managedKafka)) == null &&
                    cachedSecret(managedKafka, ssoTlsSecretName(managedKafka)) == null;
        }

        return isDeleted;
    }

    public void createOrUpdate(ManagedKafka managedKafka) {
        Secret currentMasterSecret = cachedOrRemoteSecret(managedKafka, masterSecretName(managedKafka));
        reconcile(managedKafka, kafkaTlsSecretName(managedKafka), currentMasterSecret, SecuritySecretManager::kafkaTlsSecretFrom);
        reconcile(managedKafka, ssoClientSecretName(managedKafka), currentMasterSecret, SecuritySecretManager::ssoClientSecretFrom);
        reconcile(managedKafka, ssoTlsSecretName(managedKafka), currentMasterSecret, SecuritySecretManager::ssoTlsSecretFrom);
        reconcile(managedKafka, canarySaslSecretName(managedKafka), currentMasterSecret, SecuritySecretManager::canarySaslSecretFrom);
    }

    void reconcile(ManagedKafka managedKafka, String secretName, Secret masterSecret, SecretSource builder) {
        Secret currentSecret = cachedSecret(managedKafka, secretName);
        Secret updated = builder.apply(managedKafka, currentSecret, masterSecret);

        if (updated != null) {
            createOrUpdate(updated);
        } else if (currentSecret != null){
            secretResource(managedKafka, secretName).delete();
        }
    }

    public void delete(ManagedKafka managedKafka) {
        if (isKafkaExternalCertificateEnabled(managedKafka)) {
            secretResource(managedKafka, kafkaTlsSecretName(managedKafka)).delete();
        }

        if (isKafkaAuthenticationEnabled(managedKafka)) {
            secretResource(managedKafka, ssoClientSecretName(managedKafka)).delete();
            if (managedKafka.getSpec().getOauth().getTlsTrustedCertificate() != null) {
                secretResource(managedKafka, ssoTlsSecretName(managedKafka)).delete();
            }
        }
    }

    public boolean secretKeysExist(ManagedKafka managedKafka, Map<String, List<String>> secretKeys) {
        for (Map.Entry<String, List<String>> entry : secretKeys.entrySet()) {
            String secretName = entry.getKey();
            Secret secret = cachedOrRemoteSecret(managedKafka, secretName);

            if (secret == null) {
                return false;
            }

            for (String key : entry.getValue()) {
                if (secret.getData().get(key) == null) {
                    return false;
                }
            }
        }

        return true;
    }

    public String digestSecretsVersions(ManagedKafka managedKafka, Map<String, List<String>> secretKeys) {
        final MessageDigest secretsDigest;

        try {
            secretsDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        secretKeys.keySet().stream()
            .map(name -> cachedOrRemoteSecret(managedKafka, name))
            .filter(Objects::nonNull)
            .map(Secret::getData)
            .map(Map::entrySet)
            .flatMap(Collection::stream)
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .forEach(entry ->
                secretsDigest.update(entry.getValue().getBytes(StandardCharsets.UTF_8)));

        return String.format("%040x", new BigInteger(1, secretsDigest.digest()));
    }

    public String getServiceAccountPrincipal(ManagedKafka managedKafka, ServiceAccount account) {
        return getSecretValue(managedKafka,
                account,
                ServiceAccount::getPrincipalRef,
                ServiceAccount::getPrincipal);
    }

    public <T> String getSecretValue(ManagedKafka managedKafka, T model, Function<T, SecretKeySelector> selectorLookup, Function<T, String> valueLookup) {
        Secret masterSecret = cachedOrRemoteSecret(managedKafka, masterSecretName(managedKafka));
        return getSecretValue(masterSecret, model, selectorLookup, valueLookup);
    }

    static <T> String getSecretValue(Secret masterSecret, T model, Function<T, SecretKeySelector> selectorLookup, Function<T, String> valueLookup) {
        if (model == null) {
            return null;
        }

        SecretKeySelector selector = selectorLookup.apply(model);

        if (selector != null
                && selector.getKey() != null
                && masterSecret != null
                && masterSecret.getData().containsKey(selector.getKey())) {
            return masterSecret.getData().get(selector.getKey());
        }

        return valueLookup.apply(model);
    }

    private Secret cachedOrRemoteSecret(ManagedKafka managedKafka, String name) {
        Secret secret = cachedSecret(managedKafka, name);

        if (secret == null) {
            secret = secretResource(managedKafka, name).get();
        }

        return secret;
    }

    private Secret cachedSecret(ManagedKafka managedKafka, String name) {
        return informerManager.getLocalSecret(kafkaClusterNamespace(managedKafka), name);
    }

    private Resource<Secret> secretResource(ManagedKafka managedKafka, String name) {
        return kubernetesClient.secrets()
                .inNamespace(kafkaClusterNamespace(managedKafka))
                .withName(name);
    }

    private void createOrUpdate(Secret secret) {
        OperandUtils.createOrUpdate(kubernetesClient.secrets(), secret);
    }

    private static Secret buildSecretFrom(String name, String type, ManagedKafka managedKafka, Secret current, Map<String, String> dataSource) {
        SecretBuilder builder = current != null ? new SecretBuilder(current) : new SecretBuilder();

        Map<String, String> data = dataSource.entrySet()
                .stream()
                .map(entry -> Map.entry(entry.getKey(), encode(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Secret secret = builder
                .editOrNewMetadata()
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withName(name)
                    .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withType(type)
                .withData(data)
                .build();

        // setting the ManagedKafka has owner of the Secret resource is needed
        // by the operator sdk to handle events on the Secret resource properly
        OperandUtils.setAsOwner(managedKafka, secret);

        return secret;
    }

    private static Secret kafkaTlsSecretFrom(ManagedKafka managedKafka, Secret current, Secret currentMasterSecret) {
        String tlsCert = getSecretValue(
                currentMasterSecret,
                managedKafka.getSpec().getEndpoint().getTls(),
                TlsKeyPair::getCertRef,
                TlsKeyPair::getCert);
        String tlsKey = getSecretValue(
                currentMasterSecret,
                managedKafka.getSpec().getEndpoint().getTls(),
                TlsKeyPair::getKeyRef,
                TlsKeyPair::getKey);

        if (tlsCert == null || tlsKey == null) {
            return null;
        }

        return buildSecretFrom(kafkaTlsSecretName(managedKafka),
                "kubernetes.io/tls",
                managedKafka,
                current,
                Map.of("tls.crt", tlsCert,
                       "tls.key", tlsKey));
    }

    private static Secret ssoClientSecretFrom(ManagedKafka managedKafka, Secret current, Secret currentMasterSecret) {
        String clientSecret = getSecretValue(
                currentMasterSecret,
                managedKafka.getSpec().getOauth(),
                ManagedKafkaAuthenticationOAuth::getClientSecretRef,
                ManagedKafkaAuthenticationOAuth::getClientSecret);

        if (clientSecret == null) {
            return null;
        }

        return buildSecretFrom(ssoClientSecretName(managedKafka),
                "Opaque",
                managedKafka,
                current,
                Map.of("ssoClientSecret", clientSecret));
    }

    private static Secret ssoTlsSecretFrom(ManagedKafka managedKafka, Secret current, Secret currentMasterSecret) {
        if (!isKafkaAuthenticationEnabled(managedKafka)) {
            return null;
        }

        return buildSecretFrom(ssoTlsSecretName(managedKafka),
                "Opaque",
                managedKafka,
                current,
                Map.of("keycloak.crt", managedKafka.getSpec().getOauth().getTlsTrustedCertificate()));
    }

    private static Secret canarySaslSecretFrom(ManagedKafka managedKafka, Secret current, Secret currentMasterSecret) {
        return managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary)
            .map(canary -> {
                String saslPrincipal = getSecretValue(currentMasterSecret,
                        canary,
                        ServiceAccount::getPrincipalRef,
                        ServiceAccount::getPrincipal);
                String saslPassword = getSecretValue(currentMasterSecret,
                        canary,
                        ServiceAccount::getPasswordRef,
                        ServiceAccount::getPassword);

                if (saslPrincipal == null || saslPassword == null) {
                    return null;
                }

                return buildSecretFrom(canarySaslSecretName(managedKafka),
                        "Opaque",
                        managedKafka,
                        current,
                        Map.of(SASL_PRINCIPAL, saslPrincipal,
                                SASL_PASSWORD, saslPassword));
            })
            .orElse(null);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
