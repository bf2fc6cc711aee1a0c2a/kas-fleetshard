package org.bf2.sync;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;

import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

public class InlineSecretManager {

    @Inject
    KubernetesClient kubernetesClient;

    public static String inlineSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + " -inline-secret";
    }

    /*public static boolean isKafkaAuthenticationEnabled(ManagedKafka managedKafka) {
        return managedKafka.getSpec().getOauth() != null;
    }

    public static boolean isKafkaExternalCertificateEnabled(ManagedKafka managedKafka) {
        return managedKafka.getSpec().getEndpoint().getTls() != null;
    }

    public static boolean isCanaryServiceAccountPresent(ManagedKafka managedKafka){
        return managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary).isPresent();
    }*/

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    public void createInlineSecret(ManagedKafka managedKafka) {
        Secret secret = buildSecret(inlineSecretName(managedKafka),
                            "Opaque",
                             managedKafka,
                             createDataSource(managedKafka));
        OperandUtils.createOrUpdate(kubernetesClient.secrets(), secret);
    }

    private static Secret buildSecret(String name, String type, ManagedKafka managedKafka, Map<String, String> dataSource) {
        SecretBuilder builder = new SecretBuilder();
        // The secret builder above doesn't replace the current inlineSecret, it creates a new one.

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
        //Setting the managed kafka
        return secret;
    }

    private static Map createDataSource(ManagedKafka managedKafka) {
        return Map.of("tls.crt", managedKafka.getSpec().getEndpoint().getTls().getCert(),
                      "tls.key", managedKafka.getSpec().getEndpoint().getTls().getKey(),
                      "ssoClientId", managedKafka.getSpec().getOauth().getClientId(),
                      "ssoClientSecret", managedKafka.getSpec().getOauth().getClientSecret(),
                      "sasl.principal", managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary).get().getPrincipal(),
                      "sasl.password", managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary).get().getPassword());
    }

    private void removeSecretsFromManagedKafka(ManagedKafka managedKafka){
        // TODO: Remove out credentials from the endpoint.
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
