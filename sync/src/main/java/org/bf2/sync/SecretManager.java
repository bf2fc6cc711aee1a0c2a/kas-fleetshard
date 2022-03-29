package org.bf2.sync;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretKeySelectorBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;
import org.bf2.operator.resources.v1alpha1.TlsKeyPair;
import org.bf2.sync.informer.InformerManager;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class SecretManager {
    //TODO:private
    public static final String ENDPOINT_TLS_CRT = "endpoint.tls.crt";
    public static final String ENDPOINT_TLS_KEY = "endpoint.tls.key";
    public static final String OAUTH_SSO_CLIENT_ID = "oauth.ssoClientId";
    public static final String OAUTH_SSO_CLIENT_SECRET = "oauth.ssoClientSecret";
    public static final String CANARY_SASL_PRINCIPAL = "canary.sasl.principal";
    public static final String CANARY_SASL_PASSWORD = "canary.sasl.password";
    private static final String ANNOTATION_MASTER_SECRET_DIGEST = "managedkafka.bf2.org/master-secret-digest";

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    InformerManager informerManager;

    public static String masterSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-master-secret";
    }

    public static boolean isKafkaAuthenticationEnabled(ManagedKafka managedKafka) {
        return managedKafka.getSpec().getOauth() != null;
    }

    public static boolean isKafkaExternalCertificateEnabled(ManagedKafka managedKafka) {
        return managedKafka.getSpec().getEndpoint().getTls() != null;
    }

    public static boolean isCanaryServiceAccountPresent(ManagedKafka managedKafka){
        return managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary).isPresent();
    }

    public boolean masterSecretExists(ManagedKafka managedKafka){
        return cachedOrRemoteSecret(managedKafka,masterSecretName(managedKafka)) != null;
    }

    public boolean masterSecretChanged(ManagedKafka remote, ManagedKafka local){
        String localDigest = local.getMetadata().getAnnotations().get(ANNOTATION_MASTER_SECRET_DIGEST);
        String remoteDigest =  buildDigest(buildSecretData(remote));
        return !remoteDigest.equals(localDigest);
    }

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    public Secret createSecret(ManagedKafka managedKafka) {
        Secret secret = buildSecret(masterSecretName(managedKafka),
                            "Opaque",
                             managedKafka,
                             buildSecretData(managedKafka));
        OperandUtils.createOrUpdate(kubernetesClient.secrets(), secret);
        return secret;
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
                .endMetadata()
                .withType(type)
                .withData(data)
                .build();

        OperandUtils.setAsOwner(managedKafka, secret);

        return secret;
    }

    private static Map buildSecretData(ManagedKafka managedKafka) {
        return Map.of(ENDPOINT_TLS_CRT, managedKafka.getSpec().getEndpoint().getTls().getCert(),
                ENDPOINT_TLS_KEY, managedKafka.getSpec().getEndpoint().getTls().getKey(),
                OAUTH_SSO_CLIENT_ID, managedKafka.getSpec().getOauth().getClientId(),
                OAUTH_SSO_CLIENT_SECRET, managedKafka.getSpec().getOauth().getClientSecret(),
                CANARY_SASL_PRINCIPAL, managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary).get().getPrincipal(),
                CANARY_SASL_PASSWORD, managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary).get().getPassword());
    }

    public ManagedKafka removeSecretsFromManagedKafka(ManagedKafka managedKafka, Secret secret){
        if(isKafkaAuthenticationEnabled(managedKafka) && isKafkaExternalCertificateEnabled(managedKafka)){
            TlsKeyPair tlsKeyPair = managedKafka.getSpec().getEndpoint().getTls();
            tlsKeyPair.setKey(null);
            tlsKeyPair.setCert(null);
            tlsKeyPair.setKeyRef(new SecretKeySelectorBuilder()
                    .withName(secret.getMetadata().getName())
                    .withKey(ENDPOINT_TLS_KEY)
                    .build());
            tlsKeyPair.setCertRef(new SecretKeySelectorBuilder()
                    .withName(secret.getMetadata().getName())
                    .withKey(ENDPOINT_TLS_CRT)
                    .build());
        }
       managedKafka = new ManagedKafkaBuilder(managedKafka)
                .editOrNewMetadata()
                    .addToAnnotations(Map.of(ANNOTATION_MASTER_SECRET_DIGEST, buildDigest(decode(secret.getData()))))
                .endMetadata()
                .build();
        return managedKafka;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value){
        return new String(Base64.getDecoder().decode(value));
    }

    private static Map decode(Map<String, String> data){
        return data.entrySet()
                .stream()
                .map(entry -> Map.entry(entry.getKey(), decode(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public String buildDigest(Map<String, String> data) {
        final MessageDigest secretsDigest;

        try {
            secretsDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        data.forEach((k,v) -> {
            secretsDigest.update(v.getBytes(StandardCharsets.UTF_8));
        });

        return String.format("%040x", new BigInteger(1, secretsDigest.digest()));
    }

      Secret cachedOrRemoteSecret(ManagedKafka managedKafka, String name) {
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
}
