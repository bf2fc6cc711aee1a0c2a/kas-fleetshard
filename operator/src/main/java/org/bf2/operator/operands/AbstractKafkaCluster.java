package org.bf2.operator.operands;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.Context;
import io.strimzi.api.kafka.model.CertAndKeySecretSource;
import io.strimzi.api.kafka.model.CertAndKeySecretSourceBuilder;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.CertSecretSourceBuilder;
import io.strimzi.api.kafka.model.GenericSecretSource;
import io.strimzi.api.kafka.model.GenericSecretSourceBuilder;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthentication;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationOAuthBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListeners;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListenersBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBootstrapBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBroker;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBrokerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.status.Condition;
import org.bf2.operator.InformerManager;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuth;
import org.bf2.operator.secrets.SecuritySecretManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public abstract class AbstractKafkaCluster implements Operand<ManagedKafka> {

    public static final int KAFKA_BROKERS = 3;
    public static final int ZOOKEEPER_NODES = 3;

    @Inject
    Logger log;

    @Inject
    protected KafkaResourceClient kafkaResourceClient;

    @Inject
    protected KubernetesClient kubernetesClient;

    @Inject
    protected InformerManager informerManager;

    @ConfigProperty(name = "image.kafka")
    protected Optional<String> kafkaImage;

    @ConfigProperty(name = "image.zookeeper")
    protected Optional<String> zookeeperImage;

    public static String kafkaClusterName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName();
    }

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        boolean isInstalling = kafka == null || kafka.getStatus() == null ||
                kafkaCondition(kafka, c -> c.getType() != null && c.getType().equals("NotReady")
                && c.getStatus().equals("True")
                && c.getReason().equals("Creating"));
        log.tracef("KafkaCluster isInstalling = %s", isInstalling);
        return isInstalling;
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        boolean isReady = kafka != null && (kafka.getStatus() == null ||
                kafkaCondition(kafka, c -> c.getType() != null && c.getType().equals("Ready") && c.getStatus().equals("True")));
        log.tracef("KafkaCluster isReady = %s", isReady);
        return isReady;
    }

    @Override
    public boolean isError(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        boolean isError = kafka != null && kafka.getStatus() != null
            && kafkaCondition(kafka, c -> c.getType() != null && c.getType().equals("NotReady")
            && c.getStatus().equals("True")
            && !c.getReason().equals("Creating"));
        log.tracef("KafkaCluster isError = %s", isError);
        return isError;
    }

    public boolean isReconciliationPaused(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        boolean isReconciliationPaused = kafka != null && kafka.getStatus() != null
                && kafkaCondition(kafka, c -> c.getType() != null && c.getType().equals("ReconciliationPaused")
                && c.getStatus().equals("True"));
        log.tracef("KafkaCluster isReconciliationPaused = %s", isReconciliationPaused);
        return isReconciliationPaused;
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = cachedKafka(managedKafka) == null;
        log.tracef("KafkaCluster isDeleted = %s", isDeleted);
        return isDeleted;
    }

    protected boolean kafkaCondition(Kafka kafka, Predicate<Condition> predicate) {
        return kafka.getStatus().getConditions().stream().anyMatch(predicate);
    }

    protected Kafka cachedKafka(ManagedKafka managedKafka) {
        return informerManager.getLocalKafka(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
    }

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        Kafka current = cachedKafka(managedKafka);
        Kafka kafka = kafkaFrom(managedKafka, current);
        createOrUpdate(kafka);
    }

    protected abstract Kafka kafkaFrom(ManagedKafka managedKafka, Kafka current);

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kafkaResourceClient.delete(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
    }

    protected void createOrUpdate(Kafka kafka) {
        kafkaResourceClient.createOrUpdate(kafka);
    }

    protected ArrayOrObjectKafkaListeners getListeners(ManagedKafka managedKafka) {

        KafkaListenerAuthentication plainOverOauthAuthenticationListener = null;
        KafkaListenerAuthentication oauthAuthenticationListener = null;

        if (SecuritySecretManager.isKafkaAuthenticationEnabled(managedKafka)) {
            ManagedKafkaAuthenticationOAuth managedKafkaAuthenticationOAuth = managedKafka.getSpec().getOauth();

            CertSecretSource ssoTlsCertSecretSource = getSsoTlsCertSecretSource(managedKafka);

            KafkaListenerAuthenticationOAuthBuilder plainOverOauthAuthenticationListenerBuilder = new KafkaListenerAuthenticationOAuthBuilder()
                    .withClientId(managedKafkaAuthenticationOAuth.getClientId())
                    .withJwksEndpointUri(managedKafkaAuthenticationOAuth.getJwksEndpointURI())
                    .withUserNameClaim(managedKafkaAuthenticationOAuth.getUserNameClaim())
                    .withCustomClaimCheck(managedKafkaAuthenticationOAuth.getCustomClaimCheck())
                    .withValidIssuerUri(managedKafkaAuthenticationOAuth.getValidIssuerEndpointURI())
                    .withClientSecret(getSsoClientGenericSecretSource(managedKafka))
                    .withEnablePlain(true)
                    .withTokenEndpointUri(managedKafkaAuthenticationOAuth.getTokenEndpointURI());

            if (ssoTlsCertSecretSource != null) {
                plainOverOauthAuthenticationListenerBuilder.withTlsTrustedCertificates(ssoTlsCertSecretSource);
            }
            plainOverOauthAuthenticationListener = plainOverOauthAuthenticationListenerBuilder.build();

            KafkaListenerAuthenticationOAuthBuilder oauthAuthenticationListenerBuilder = new KafkaListenerAuthenticationOAuthBuilder()
                    .withClientId(managedKafkaAuthenticationOAuth.getClientId())
                    .withJwksEndpointUri(managedKafkaAuthenticationOAuth.getJwksEndpointURI())
                    .withUserNameClaim(managedKafkaAuthenticationOAuth.getUserNameClaim())
                    .withCustomClaimCheck(managedKafkaAuthenticationOAuth.getCustomClaimCheck())
                    .withValidIssuerUri(managedKafkaAuthenticationOAuth.getValidIssuerEndpointURI())
                    .withClientSecret(getSsoClientGenericSecretSource(managedKafka));

            if (ssoTlsCertSecretSource != null) {
                oauthAuthenticationListenerBuilder.withTlsTrustedCertificates(ssoTlsCertSecretSource);
            }
            oauthAuthenticationListener = oauthAuthenticationListenerBuilder.build();
        }

        KafkaListenerType externalListenerType = kubernetesClient.isAdaptable(OpenShiftClient.class) ? KafkaListenerType.ROUTE : KafkaListenerType.INGRESS;

        return new ArrayOrObjectKafkaListenersBuilder()
                .withGenericKafkaListeners(
                        new GenericKafkaListenerBuilder()
                                .withName("plain")
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .build(),
                        new GenericKafkaListenerBuilder()
                                .withName("external")
                                .withPort(9094)
                                .withType(externalListenerType)
                                .withTls(true)
                                .withAuth(plainOverOauthAuthenticationListener)
                                .withConfiguration(
                                        new GenericKafkaListenerConfigurationBuilder()
                                                .withBootstrap(new GenericKafkaListenerConfigurationBootstrapBuilder()
                                                        .withHost(managedKafka.getSpec().getEndpoint().getBootstrapServerHost())
                                                        .build()
                                                )
                                                .withBrokers(getBrokerOverrides(managedKafka))
                                                .withBrokerCertChainAndKey(getTlsCertAndKeySecretSource(managedKafka))
                                                .build()
                                )
                                .build(),
                        new GenericKafkaListenerBuilder()
                                .withName("oauth")
                                .withPort(9095)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .withAuth(oauthAuthenticationListener)
                                .build(),
                        new GenericKafkaListenerBuilder()
                                .withName("sre")
                                .withPort(9096)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .build()
                ).build();
    }

    protected List<GenericKafkaListenerConfigurationBroker> getBrokerOverrides(ManagedKafka managedKafka) {
        List<GenericKafkaListenerConfigurationBroker> brokerOverrides = new ArrayList<>(KAFKA_BROKERS);
        for (int i = 0; i < KAFKA_BROKERS; i++) {
            brokerOverrides.add(
                    new GenericKafkaListenerConfigurationBrokerBuilder()
                            .withHost(String.format("broker-%d-%s", i, managedKafka.getSpec().getEndpoint().getBootstrapServerHost()))
                            .withBroker(i)
                            .build()
            );
        }
        return brokerOverrides;
    }

    protected CertAndKeySecretSource getTlsCertAndKeySecretSource(ManagedKafka managedKafka) {
        if (!SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            return null;
        }
        return new CertAndKeySecretSourceBuilder()
                .withSecretName(SecuritySecretManager.kafkaTlsSecretName(managedKafka))
                .withCertificate("tls.crt")
                .withKey("tls.key")
                .build();
    }

    protected GenericSecretSource getSsoClientGenericSecretSource(ManagedKafka managedKafka) {
        return new GenericSecretSourceBuilder()
                .withSecretName(SecuritySecretManager.ssoClientSecretName(managedKafka))
                .withKey("ssoClientSecret")
                .build();
    }

    protected CertSecretSource getSsoTlsCertSecretSource(ManagedKafka managedKafka) {
        if (!SecuritySecretManager.isKafkaAuthenticationEnabled(managedKafka) || managedKafka.getSpec().getOauth().getTlsTrustedCertificate() == null) {
            return null;
        }
        return new CertSecretSourceBuilder()
                .withSecretName(SecuritySecretManager.ssoTlsSecretName(managedKafka))
                .withCertificate("keycloak.crt")
                .build();
    }

}
