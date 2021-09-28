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
import io.strimzi.api.kafka.model.status.KafkaStatus;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.managers.SecuritySecretManager;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuth;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public abstract class AbstractKafkaCluster implements Operand<ManagedKafka> {

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

    @Inject
    protected KafkaInstanceConfiguration config;

    public static String kafkaClusterName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName();
    }

    public static String kafkaClusterNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }

    public boolean isStrimziUpdating(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        String pauseReason = kafka.getMetadata().getAnnotations() != null ?
                kafka.getMetadata().getAnnotations().get(StrimziManager.STRIMZI_PAUSE_REASON_ANNOTATION) : null;
        return ManagedKafkaCondition.Reason.StrimziUpdating.name().toLowerCase().equals(pauseReason) &&
                isReconciliationPaused(managedKafka);
    }

    public boolean isReadyNotUpdating(ManagedKafka managedKafka) {
        OperandReadiness readiness = getReadiness(managedKafka);
        return Status.True.equals(readiness.getStatus()) && !Reason.StrimziUpdating.equals(readiness.getReason());
    }

    @Override
    public OperandReadiness getReadiness(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);

        if (kafka == null) {
            return new OperandReadiness(Status.False, Reason.Installing, String.format("Kafka %s does not exist", kafkaClusterName(managedKafka)));
        }

        Optional<Condition> notReady = kafkaCondition(kafka, c -> "NotReady".equals(c.getType()));

        if (notReady.filter(c -> "True".equals(c.getStatus())).isPresent()) {
            Condition c = notReady.get();
            return new OperandReadiness(Status.False, "Creating".equals(c.getReason()) ? Reason.Installing : Reason.Error, c.getMessage());
        }

        Optional<Condition> ready = kafkaCondition(kafka, c -> "Ready".equals(c.getType()));

        if (ready.filter(c -> "True".equals(c.getStatus())).isPresent()) {
            return new OperandReadiness(Status.True, null, null);
        }

        if (isStrimziUpdating(managedKafka)) {
            return new OperandReadiness(Status.True, Reason.StrimziUpdating, null);
        }

        return new OperandReadiness(Status.False, Reason.Installing, String.format("Kafka %s is not providing status", kafkaClusterName(managedKafka)));
    }

    public boolean isReconciliationPaused(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        boolean isReconciliationPaused = kafka != null && kafka.getStatus() != null
                && hasKafkaCondition(kafka, c -> c.getType() != null && "ReconciliationPaused".equals(c.getType())
                && "True".equals(c.getStatus()));
        log.tracef("KafkaCluster isReconciliationPaused = %s", isReconciliationPaused);
        return isReconciliationPaused;
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = cachedKafka(managedKafka) == null;
        log.tracef("KafkaCluster isDeleted = %s", isDeleted);
        return isDeleted;
    }

    protected boolean hasKafkaCondition(Kafka kafka, Predicate<Condition> predicate) {
        return kafkaCondition(kafka, predicate).isPresent();
    }

    protected Optional<Condition> kafkaCondition(Kafka kafka, Predicate<Condition> predicate) {
        return Optional.ofNullable(kafka.getStatus()).map(KafkaStatus::getConditions).flatMap(l -> l.stream().filter(predicate).findFirst());
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

    public abstract Kafka kafkaFrom(ManagedKafka managedKafka, Kafka current);

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kafkaResourceClient.delete(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
    }

    protected void createOrUpdate(Kafka kafka) {
        kafkaResourceClient.createOrUpdate(kafka);
    }

    protected ArrayOrObjectKafkaListeners buildListeners(ManagedKafka managedKafka) {

        KafkaListenerAuthentication plainOverOauthAuthenticationListener = null;
        KafkaListenerAuthentication oauthAuthenticationListener = null;

        if (SecuritySecretManager.isKafkaAuthenticationEnabled(managedKafka)) {
            ManagedKafkaAuthenticationOAuth managedKafkaAuthenticationOAuth = managedKafka.getSpec().getOauth();

            CertSecretSource ssoTlsCertSecretSource = buildSsoTlsCertSecretSource(managedKafka);

            KafkaListenerAuthenticationOAuthBuilder plainOverOauthAuthenticationListenerBuilder = new KafkaListenerAuthenticationOAuthBuilder()
                    .withClientId(managedKafkaAuthenticationOAuth.getClientId())
                    .withJwksEndpointUri(managedKafkaAuthenticationOAuth.getJwksEndpointURI())
                    .withUserNameClaim(managedKafkaAuthenticationOAuth.getUserNameClaim())
                    .withFallbackUserNameClaim(managedKafkaAuthenticationOAuth.getFallbackUserNameClaim())
                    .withCustomClaimCheck(managedKafkaAuthenticationOAuth.getCustomClaimCheck())
                    .withValidIssuerUri(managedKafkaAuthenticationOAuth.getValidIssuerEndpointURI())
                    .withClientSecret(buildSsoClientGenericSecretSource(managedKafka))
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
                    .withFallbackUserNameClaim(managedKafkaAuthenticationOAuth.getFallbackUserNameClaim())
                    .withCustomClaimCheck(managedKafkaAuthenticationOAuth.getCustomClaimCheck())
                    .withValidIssuerUri(managedKafkaAuthenticationOAuth.getValidIssuerEndpointURI())
                    .withClientSecret(buildSsoClientGenericSecretSource(managedKafka));

            if (ssoTlsCertSecretSource != null) {
                oauthAuthenticationListenerBuilder.withTlsTrustedCertificates(ssoTlsCertSecretSource);
            }
            oauthAuthenticationListener = oauthAuthenticationListenerBuilder.build();
        }

        KafkaListenerType externalListenerType = kubernetesClient.isAdaptable(OpenShiftClient.class) ? KafkaListenerType.ROUTE : KafkaListenerType.INGRESS;

        // Limit client connections per listener
        Integer totalMaxConnections = Objects.requireNonNullElse(managedKafka.getSpec().getCapacity().getTotalMaxConnections(), this.config.getKafka().getMaxConnections())/this.config.getKafka().getReplicas();
        // Limit connection attempts per listener
        Integer maxConnectionAttemptsPerSec = Objects.requireNonNullElse(managedKafka.getSpec().getCapacity().getMaxConnectionAttemptsPerSec(), this.config.getKafka().getConnectionAttemptsPerSec())/this.config.getKafka().getReplicas();

        GenericKafkaListenerConfigurationBuilder listenerConfigBuilder =  new GenericKafkaListenerConfigurationBuilder()
                .withBootstrap(new GenericKafkaListenerConfigurationBootstrapBuilder()
                        .withHost(managedKafka.getSpec().getEndpoint().getBootstrapServerHost())
                        .build()
                )
                .withBrokers(buildBrokerOverrides(managedKafka))
                .withBrokerCertChainAndKey(buildTlsCertAndKeySecretSource(managedKafka))
                .withMaxConnections(totalMaxConnections)
                .withMaxConnectionCreationRate(maxConnectionAttemptsPerSec);

        return new ArrayOrObjectKafkaListenersBuilder()
                .withGenericKafkaListeners(
                        new GenericKafkaListenerBuilder()
                                .withName("tls")
                                .withPort(9093)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(true)
                                .withAuth(plainOverOauthAuthenticationListener)
                                .build(),
                        new GenericKafkaListenerBuilder()
                                .withName("external")
                                .withPort(9094)
                                .withType(externalListenerType)
                                .withTls(true)
                                .withAuth(plainOverOauthAuthenticationListener)
                                .withConfiguration(listenerConfigBuilder.build())
                                .build(),
                        new GenericKafkaListenerBuilder()
                                .withName("oauth")
                                .withPort(9095)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(true)
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

    protected List<GenericKafkaListenerConfigurationBroker> buildBrokerOverrides(ManagedKafka managedKafka) {
        List<GenericKafkaListenerConfigurationBroker> brokerOverrides = new ArrayList<>(this.config.getKafka().getReplicas());
        for (int i = 0; i < this.config.getKafka().getReplicas(); i++) {
            brokerOverrides.add(
                    new GenericKafkaListenerConfigurationBrokerBuilder()
                            .withHost(String.format("broker-%d-%s", i, managedKafka.getSpec().getEndpoint().getBootstrapServerHost()))
                            .withBroker(i)
                            .build()
            );
        }
        return brokerOverrides;
    }

    protected CertAndKeySecretSource buildTlsCertAndKeySecretSource(ManagedKafka managedKafka) {
        if (!SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            return null;
        }
        return new CertAndKeySecretSourceBuilder()
                .withSecretName(SecuritySecretManager.kafkaTlsSecretName(managedKafka))
                .withCertificate("tls.crt")
                .withKey("tls.key")
                .build();
    }

    protected GenericSecretSource buildSsoClientGenericSecretSource(ManagedKafka managedKafka) {
        return new GenericSecretSourceBuilder()
                .withSecretName(SecuritySecretManager.ssoClientSecretName(managedKafka))
                .withKey("ssoClientSecret")
                .build();
    }

    protected CertSecretSource buildSsoTlsCertSecretSource(ManagedKafka managedKafka) {
        if (!SecuritySecretManager.isKafkaAuthenticationEnabled(managedKafka) || managedKafka.getSpec().getOauth().getTlsTrustedCertificate() == null) {
            return null;
        }
        return new CertSecretSourceBuilder()
                .withSecretName(SecuritySecretManager.ssoTlsSecretName(managedKafka))
                .withCertificate("keycloak.crt")
                .build();
    }
}
