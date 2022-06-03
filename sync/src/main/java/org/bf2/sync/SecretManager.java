package org.bf2.sync;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;
import org.bf2.sync.informer.InformerManager;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class SecretManager {

    private static final String ENDPOINT_TLS_CRT = "endpoint.tls.crt";
    private static final String ENDPOINT_TLS_KEY = "endpoint.tls.key";
    private static final String OAUTH_SSO_CLIENT_ID = "oauth.ssoClientId";
    private static final String OAUTH_SSO_CLIENT_SECRET = "oauth.ssoClientSecret";
    private static final String CANARY_SASL_PRINCIPAL = "canary.sasl.principal";
    private static final String CANARY_SASL_PASSWORD = "canary.sasl.password";
    static final String ANNOTATION_MASTER_SECRET_DIGEST = "managedkafka.bf2.org/master-secret-digest";

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    InformerManager informerManager;

    public static String masterSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-master-secret";
    }

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    public static boolean isKafkaOAuthClientPresent(ManagedKafka managedKafka) {
        return Optional.ofNullable(managedKafka.getSpec().getOauth())
            .map(oauth -> oauth.getClientId() != null && oauth.getClientSecret() != null)
            .orElse(false);
    }

    public static boolean isKafkaExternalCertificateEnabled(ManagedKafka managedKafka) {
        return managedKafka.getSpec().getEndpoint().getTls() != null;
    }

    public boolean isMasterSecretChanged(ManagedKafka remote, ManagedKafka local){
        String localDigest = local.getMetadata().getAnnotations().get(ANNOTATION_MASTER_SECRET_DIGEST);
        String remoteDigest = buildDigest(buildSecretData(remote));
        return !remoteDigest.equals(localDigest);
    }

    public Secret buildSecret(ManagedKafka managedKafka) {
        Secret secret = buildSecret(masterSecretName(managedKafka),
                            "Opaque",
                             managedKafka,
                             buildSecretData(managedKafka));
        return secret;
    }

    public void createOrUpdateSecret(ManagedKafka managedKafka, Secret secret) {
        OperandUtils.setAsOwner(managedKafka, secret);
        OperandUtils.createOrUpdate(kubernetesClient.secrets(), secret);
    }

    private Secret buildSecret(String name, String type, ManagedKafka managedKafka, Map<String, String> dataSource) {
        Secret current = cachedSecret(managedKafka, masterSecretName(managedKafka));
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
                    .addToLabels(OperandUtils.getMasterSecretLabel())
                .endMetadata()
                .withType(type)
                .withData(data)
                .build();

        return secret;
    }

    private Map<String,String> buildSecretData(ManagedKafka managedKafka) {
        // Add to
        Map<String, String> data = new HashMap<>();
        if (isKafkaOAuthClientPresent(managedKafka)) {
            data.putAll(Map.of(OAUTH_SSO_CLIENT_ID, managedKafka.getSpec().getOauth().getClientId(),
                    OAUTH_SSO_CLIENT_SECRET, managedKafka.getSpec().getOauth().getClientSecret()));
        }
        if (isKafkaExternalCertificateEnabled(managedKafka)) {
            data.putAll(Map.of(ENDPOINT_TLS_CRT, managedKafka.getSpec().getEndpoint().getTls().getCert(),
                    ENDPOINT_TLS_KEY, managedKafka.getSpec().getEndpoint().getTls().getKey()));
        }

        managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary)
            .ifPresent(canarySA -> {
                data.putAll(Map.of(CANARY_SASL_PRINCIPAL, canarySA.getPrincipal(),
                        CANARY_SASL_PASSWORD, canarySA.getPassword()));
            });

        return data;
    }

    public ManagedKafka removeSecretsFromManagedKafka(ManagedKafka managedKafka) {
        final String masterSecretName = masterSecretName(managedKafka);
        final ManagedKafkaSpecBuilder replacement = new ManagedKafkaSpecBuilder(managedKafka.getSpec());

        if (isKafkaExternalCertificateEnabled(managedKafka)) {
            replacement.editEndpoint()
                .withNewTls()
                    .withNewCertRef(ENDPOINT_TLS_CRT, masterSecretName, null)
                    .withNewKeyRef(ENDPOINT_TLS_KEY, masterSecretName, null)
                .endTls()
                .endEndpoint();
        }

        if (isKafkaOAuthClientPresent(managedKafka)) {
            replacement.editOauth()
                    .withNewClientIdRef(OAUTH_SSO_CLIENT_ID, masterSecretName, null)
                    .withClientId(null)
                    .withNewClientSecretRef(OAUTH_SSO_CLIENT_SECRET, masterSecretName, null)
                    .withClientSecret(null)
                .endOauth();
        }

        managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary)
            .ifPresent(serviceAccount -> {
                replacement.editMatchingServiceAccount(sa -> serviceAccount.getName().equals(sa.getName()))
                    .withNewPrincipalRef(CANARY_SASL_PRINCIPAL, masterSecretName, null)
                    .withPrincipal(null)
                    .withNewPasswordRef(CANARY_SASL_PASSWORD, masterSecretName, null)
                    .withPassword(null)
                .endServiceAccount();
            });

       return new ManagedKafkaBuilder(managedKafka)
                .withSpec(replacement.build())
                .build();
    }

    public void calculateMasterSecretDigest(ManagedKafka managedKafka, Secret secret) {
        var metadata = new ObjectMetaBuilder(managedKafka.getMetadata())
                .addToAnnotations(ANNOTATION_MASTER_SECRET_DIGEST, buildDigest(decode(secret.getData())))
                .build();

        managedKafka.setMetadata(metadata);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value){
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static Map<String, String> decode(Map<String, String> data){
        return data.entrySet()
                .stream()
                .map(entry -> Map.entry(entry.getKey(), decode(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String buildDigest(Map<String, String> data) {
        final MessageDigest secretsDigest;

        try {
            secretsDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        // Sort by key to ensure iteration order for any input map
        data.entrySet()
            .stream()
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .forEach(entry ->
                secretsDigest.update(entry.getValue().getBytes(StandardCharsets.UTF_8)));

        return String.format("%040x", new BigInteger(1, secretsDigest.digest()));
    }

    private Secret cachedSecret(ManagedKafka managedKafka, String name) {
        return informerManager.getLocalSecret(kafkaClusterNamespace(managedKafka), name);
    }
}
