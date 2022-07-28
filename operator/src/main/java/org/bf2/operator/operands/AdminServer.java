package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.api.model.RouteSpec;
import io.fabric8.openshift.api.model.TLSConfig;
import io.fabric8.openshift.api.model.TLSConfigBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.StartupEvent;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.managers.IngressControllerManager;
import org.bf2.operator.managers.OperandOverrideManager;
import org.bf2.operator.managers.SecuritySecretManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuth;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides same functionalities to get a AdminServer deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@DefaultBean
public class AdminServer extends AbstractAdminServer {

    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;

    private static final String HTTP_PORT_NAME = "http";
    private static final String HTTPS_PORT_NAME = "https";
    private static final String MANAGEMENT_PORT_NAME = "management";

    private static final IntOrString HTTP_PORT_TARGET = new IntOrString(HTTP_PORT_NAME);
    private static final IntOrString HTTPS_PORT_TARGET = new IntOrString(HTTPS_PORT_NAME);

    static final String RATE_LIMIT_ANNOTATION = "haproxy.router.openshift.io/rate-limit-connections";
    static final String RATE_LIMIT_ANNOTATION_CONCURRENT_TCP = RATE_LIMIT_ANNOTATION + ".concurrent-tcp";
    static final String RATE_LIMIT_ANNOTATION_TCP_RATE = RATE_LIMIT_ANNOTATION + ".rate-tcp";

    static final String CUSTOM_CONFIG_VOLUME_NAME = "custom-config";
    static final String TLS_CONFIG_VOLUME_NAME = "tls-config";
    static final String TLS_CONFIG_MOUNT_PATH = "/opt/kafka-admin-api/tls-config/";

    @Inject
    Logger log;

    @ConfigProperty(name = "adminserver.cors.allowlist")
    Optional<String> corsAllowList;

    OpenShiftClient openShiftClient;

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    @Inject
    protected SecuritySecretManager securitySecretManager;

    @Inject
    protected KafkaInstanceConfigurations configs;

    @Inject
    protected Instance<IngressControllerManager> ingressControllerManagerInstance;

    @Inject
    protected OperandOverrideManager overrideManager;

    void onStart(@Observes StartupEvent ev) {
        if (kubernetesClient.isAdaptable(OpenShiftClient.class)) {
            openShiftClient = kubernetesClient.adapt(OpenShiftClient.class);
        }
    }

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        if (managedKafka.isReserveDeployment()) {
            Deployment current = cachedDeployment(managedKafka);
            Deployment deployment = deploymentFrom(managedKafka, null);

            deployment = ReservedDeploymentConverter.asReservedDeployment(current, deployment, managedKafka);
            if (!Objects.equals(current, deployment)) {
                createOrUpdate(deployment);
            }
            return;
        }

        if (!secretDependenciesPresent(managedKafka)) {
            return;
        }

        super.createOrUpdate(managedKafka);

        if (openShiftClient != null) {
            Route currentRoute = cachedRoute(managedKafka);
            Route route = routeFrom(managedKafka, currentRoute);

            OperandUtils.createOrUpdate(openShiftClient.routes(), route);
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context context) {
        super.delete(managedKafka, context);

        if (openShiftClient != null) {
            adminRouteResource(managedKafka).delete();
        }
    }

    /* test */
    @Override
    public Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current) {
        String adminServerName = adminServerName(managedKafka);

        DeploymentBuilder builder = current != null ? new DeploymentBuilder(current) : new DeploymentBuilder();

        Affinity affinity = OperandUtils.buildAffinity(informerManager.getLocalAgent(), managedKafka,
                this.configs.getConfig(managedKafka).getAdminserver().isColocateWithZookeeper());

        builder
                .editOrNewMetadata()
                    .withName(adminServerName)
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withLabels(buildLabels(adminServerName, managedKafka))
                .endMetadata()
                .editOrNewSpec()
                    .withReplicas(1)
                    .editOrNewSelector()
                        .withMatchLabels(buildSelectorLabels(adminServerName))
                    .endSelector()
                    .editOrNewTemplate()
                        .editOrNewMetadata()
                            .withAnnotations(buildAnnotations(managedKafka))
                            .withLabels(buildLabels(adminServerName, managedKafka))
                        .endMetadata()
                        .editOrNewSpec()
                            .withContainers(buildContainers(managedKafka))
                            .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                            .withVolumes(buildVolumes(managedKafka))
                            .withAffinity(affinity)
                            .withTolerations(OperandUtils.profileTolerations(managedKafka))
                        .endSpec()
                    .endTemplate()
                .endSpec();


        Deployment deployment = builder.build();

        // setting the ManagedKafka has owner of the Admin Server deployment resource is needed
        // by the operator sdk to handle events on the Deployment resource properly
        OperandUtils.setAsOwner(managedKafka, deployment);

        return deployment;
    }

    /* test */
    @Override
    public Service serviceFrom(ManagedKafka managedKafka, Service current) {
        String adminServerName = adminServerName(managedKafka);

        ServiceBuilder builder = current != null ? new ServiceBuilder(current) : new ServiceBuilder();

        Service service = builder
                .editOrNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    .withLabels(buildLabels(adminServerName, managedKafka))
                .endMetadata()
                .editOrNewSpec()
                    .withClusterIP(null) // to prevent 422 errors
                    .withSelector(buildSelectorLabels(adminServerName))
                    .withPorts(buildServicePorts(managedKafka))
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Admin Server service resource is needed
        // by the operator sdk to handle events on the Service resource properly
        OperandUtils.setAsOwner(managedKafka, service);

        return service;
    }

    /* test */
    protected Route routeFrom(ManagedKafka managedKafka, Route current) {
        String adminServerName = adminServerName(managedKafka);

        RouteBuilder builder = current != null ? new RouteBuilder(current) : new RouteBuilder();

        final IntOrString targetPort;
        final TLSConfig tlsConfig;

        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            targetPort = HTTPS_PORT_TARGET;
            tlsConfig = new TLSConfigBuilder().withTermination("passthrough").build();
        } else if (config.getAdminserver().isEdgeTlsEnabled()) {
            targetPort = HTTP_PORT_TARGET;
            tlsConfig = new TLSConfigBuilder().withTermination("edge").build();
        } else {
            targetPort = HTTP_PORT_TARGET;
            tlsConfig = null;
        }

        Route route = builder
                .editOrNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    .withLabels(buildRouteLabels())
                    .withAnnotations(buildRouteAnnotations(config))
                .endMetadata()
                .withNewSpec()
                    .withNewTo()
                        .withKind("Service")
                        .withName(adminServerName)
                    .endTo()
                    .withNewPort()
                        .withTargetPort(targetPort)
                    .endPort()
                    .withHost("admin-server-" + managedKafka.getSpec().getEndpoint().getBootstrapServerHost())
                    .withTls(tlsConfig)
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Admin Server route resource is needed
        // by the operator sdk to handle events on the Route resource properly
        OperandUtils.setAsOwner(managedKafka, route);

        return route;
    }

    protected List<Container> buildContainers(ManagedKafka managedKafka) {
        Probe readinessProbe = buildProbe("/health/ready", HTTP_PORT_TARGET);
        Probe livenessProbe = buildProbe("/health/live", HTTP_PORT_TARGET);

        Container container = new ContainerBuilder()
                .withName("admin-server")
                .withImage(overrideManager.getAdminServerImage(managedKafka.getSpec().getVersions().getStrimzi()))
                .withEnv(buildEnvVar(managedKafka))
                .withPorts(buildContainerPorts(managedKafka))
                .withResources(this.configs.getConfig(managedKafka).getAdminserver().buildResources())
                .withReadinessProbe(readinessProbe)
                .withLivenessProbe(livenessProbe)
                .withVolumeMounts(buildVolumeMounts(managedKafka))
                .build();

        return Collections.singletonList(container);
    }

    private Probe buildProbe(String path, IntOrString portTarget) {
        return new ProbeBuilder()
                .withHttpGet(
                        new HTTPGetActionBuilder()
                        .withPath(path)
                        .withPort(portTarget)
                        .build()
                )
                .withTimeoutSeconds(5)
                .withInitialDelaySeconds(15)
                .build();
    }

    private List<VolumeMount> buildVolumeMounts(ManagedKafka managedKafka) {
        List<VolumeMount> volumeMounts = new ArrayList<>();

        volumeMounts.add(new VolumeMountBuilder()
                .withName(CUSTOM_CONFIG_VOLUME_NAME)
                /* Matches location expected by kafka-admin-api container. */
                .withMountPath("/opt/kafka-admin-api/custom-config/")
            .build());

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            volumeMounts.add(new VolumeMountBuilder()
                    .withName(TLS_CONFIG_VOLUME_NAME)
                    .withMountPath(TLS_CONFIG_MOUNT_PATH)
                    .withReadOnly(Boolean.TRUE)
                .build());
        }

        return volumeMounts;
    }

    private List<Volume> buildVolumes(ManagedKafka managedKafka) {
        List<Volume> volumes = new ArrayList<>();

        volumes.add(new VolumeBuilder()
                .withName(CUSTOM_CONFIG_VOLUME_NAME)
                .editOrNewConfigMap()
                    .withName(adminServerName(managedKafka))
                    .withOptional(Boolean.TRUE)
                .endConfigMap()
                .build());

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            volumes.add(new VolumeBuilder()
                    .withName(TLS_CONFIG_VOLUME_NAME)
                    .editOrNewSecret()
                        .withSecretName(SecuritySecretManager.kafkaTlsSecretName(managedKafka))
                    .endSecret()
                    .build());
        }

        return volumes;
    }

    private Map<String, String> buildSelectorLabels(String adminServerName) {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        labels.put("app", adminServerName);
        return labels;
    }

    Map<String, String> buildLabels(String adminServerName, ManagedKafka managedKafka) {
        Map<String, String> labels = buildSelectorLabels(adminServerName);
        Optional.ofNullable(managedKafka.getMetadata().getLabels()).ifPresent(labels::putAll);
        labels.put("app.kubernetes.io/component", "adminserver");
        return labels;
    }

    private Map<String, List<String>> getDependsOnSecrets(ManagedKafka managedKafka) {
        Map<String, List<String>> dependsOnSecrets = new HashMap<>();

        dependsOnSecrets.put(SecuritySecretManager.strimziClusterCaCertSecret(managedKafka), List.of("ca.crt"));

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            dependsOnSecrets.put(SecuritySecretManager.kafkaTlsSecretName(managedKafka), List.of("tls.crt", "tls.key"));
        }

        if (SecuritySecretManager.isKafkaAuthenticationEnabled(managedKafka)) {
            ManagedKafkaAuthenticationOAuth oauth = managedKafka.getSpec().getOauth();

            if (oauth.getTlsTrustedCertificate() != null) {
                dependsOnSecrets.put(SecuritySecretManager.ssoTlsSecretName(managedKafka), List.of("keycloak.crt"));
            }
        }

        return dependsOnSecrets;
    }

    private boolean secretDependenciesPresent(ManagedKafka managedKafka) {
        return securitySecretManager.secretKeysExist(managedKafka, getDependsOnSecrets(managedKafka));
    }

    private Map<String, String> buildAnnotations(ManagedKafka managedKafka) {
        return Map.of(
                SecuritySecretManager.ANNOTATION_SECRET_DEP_DIGEST,
                securitySecretManager.digestSecretsVersions(managedKafka, getDependsOnSecrets(managedKafka)));
    }

    private Map<String, String> buildRouteLabels() {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        labels.put("ingressType", "sharded");

        if (ingressControllerManagerInstance.isResolvable()) {
            labels.putAll(ingressControllerManagerInstance.get().getRouteMatchLabels());
        }
        return labels;
    }

    Map<String, String> buildRouteAnnotations(KafkaInstanceConfiguration config) {
        Map<String, String> annotations;

        if (config.getAdminserver().isRateLimitEnabled()) {
            String concurrentTcp = String.valueOf(config.getAdminserver().getRateLimitConcurrentTcp());
            // TCP limit expressed in terms of a 3s window
            int rateTcp = 3 * config.getAdminserver().getRateLimitRequestsPerSec();

            annotations = Map.ofEntries(
                    Map.entry(RATE_LIMIT_ANNOTATION, "true"),
                    Map.entry(RATE_LIMIT_ANNOTATION_CONCURRENT_TCP, concurrentTcp),
                    Map.entry(RATE_LIMIT_ANNOTATION_TCP_RATE, String.valueOf(rateTcp)));
        } else {
            annotations = Collections.emptyMap();
        }

        return annotations;
    }

    private List<EnvVar> buildEnvVar(ManagedKafka managedKafka) {
        List<EnvVar> envVars = new ArrayList<>();

        KafkaInstanceConfiguration config = this.configs.getConfig(managedKafka);
        addEnvVar(envVars, "KAFKA_ADMIN_REPLICATION_FACTOR", String.valueOf(config.getKafka().getScalingAndReplicationFactor()));
        addEnvVar(envVars, "KAFKA_ADMIN_BOOTSTRAP_SERVERS", managedKafka.getMetadata().getName() + "-kafka-bootstrap:9095");
        addEnvVar(envVars, "KAFKA_ADMIN_BROKER_TLS_ENABLED", "true");
        addEnvVarSecret(envVars, "KAFKA_ADMIN_BROKER_TRUSTED_CERT", SecuritySecretManager.strimziClusterCaCertSecret(managedKafka), "ca.crt");
        addEnvVar(envVars, "KAFKA_ADMIN_ACL_RESOURCE_OPERATIONS", config.getKafka().getAcl().getResourceOperations());

        Integer maxPartitions = managedKafka.getSpec().getCapacity().getMaxPartitions();
        if (maxPartitions != null) {
            addEnvVar(envVars, "KAFKA_ADMIN_NUM_PARTITIONS_MAX", maxPartitions.toString());
        }

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            addEnvVar(envVars, "KAFKA_ADMIN_TLS_CERT", TLS_CONFIG_MOUNT_PATH + "tls.crt");
            addEnvVar(envVars, "KAFKA_ADMIN_TLS_KEY", TLS_CONFIG_MOUNT_PATH + "tls.key");
            addEnvVar(envVars, "KAFKA_ADMIN_TLS_VERSION", "TLSv1.3,TLSv1.2");
        }

        if (SecuritySecretManager.isKafkaAuthenticationEnabled(managedKafka)) {
            ManagedKafkaAuthenticationOAuth oauth = managedKafka.getSpec().getOauth();

            if (oauth.getTlsTrustedCertificate() != null) {
                addEnvVarSecret(envVars, "KAFKA_ADMIN_OAUTH_TRUSTED_CERT", SecuritySecretManager.ssoTlsSecretName(managedKafka), "keycloak.crt");
            }

            addEnvVar(envVars, "KAFKA_ADMIN_OAUTH_JWKS_ENDPOINT_URI", oauth.getJwksEndpointURI());
            addEnvVar(envVars, "KAFKA_ADMIN_OAUTH_VALID_ISSUER_URI", oauth.getValidIssuerEndpointURI());
            addEnvVar(envVars, "KAFKA_ADMIN_OAUTH_TOKEN_ENDPOINT_URI", oauth.getTokenEndpointURI());
        } else {
            addEnvVar(envVars, "KAFKA_ADMIN_OAUTH_ENABLED", "false");
        }

        corsAllowList.ifPresent(s -> addEnvVar(envVars, "CORS_ALLOW_LIST_REGEX", s));
        return this.overrideManager.getAdminServerOverride(managedKafka.getSpec().getVersions().getStrimzi()).applyEnvironmentTo(envVars);
    }

    private void addEnvVar(List<EnvVar> envVars, String name, String value) {
        envVars.add(new EnvVarBuilder().withName(name).withValue(value).build());
    }

    private void addEnvVarSecret(List<EnvVar> envVars, String envName, String secretName, String secretEntry) {
        envVars.add(new EnvVarBuilder()
                        .withName(envName)
                        .withNewValueFrom()
                            .withNewSecretKeyRef(secretEntry, secretName, false)
                            .endValueFrom()
                        .build());
    }

    private List<ContainerPort> buildContainerPorts(ManagedKafka managedKafka) {
        List<ContainerPort> ports = new ArrayList<>(3);

        // HTTP port always exposed for health probes
        ports.add(new ContainerPortBuilder()
                .withName(HTTP_PORT_NAME)
                .withContainerPort(HTTP_PORT)
                .build());

        // HTTP "management" alias port exposed for metrics pod monitor. Remove when no longer needed.
        ports.add(new ContainerPortBuilder()
                .withName(MANAGEMENT_PORT_NAME)
                .withContainerPort(HTTP_PORT)
                .build());

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            // HTTPS port exposed when the route/service will be configured for TLS
            ports.add(new ContainerPortBuilder()
                    .withName(HTTPS_PORT_NAME)
                    .withContainerPort(HTTPS_PORT)
                    .build());
        }

        return ports;
    }

    private List<ServicePort> buildServicePorts(ManagedKafka managedKafka) {
        final String apiPortName;
        final int apiPort;
        final IntOrString apiTargetPort;

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            apiPortName = HTTPS_PORT_NAME;
            apiPort = HTTPS_PORT;
            apiTargetPort = HTTPS_PORT_TARGET;
        } else {
            apiPortName = HTTP_PORT_NAME;
            apiPort = HTTP_PORT;
            apiTargetPort = HTTP_PORT_TARGET;
        }

        return Collections.singletonList(new ServicePortBuilder()
                           .withName(apiPortName)
                           .withProtocol("TCP")
                           .withPort(apiPort)
                           .withTargetPort(apiTargetPort)
                           .build());
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = cachedDeployment(managedKafka) == null && cachedService(managedKafka) == null;
        if (openShiftClient != null) {
            isDeleted = isDeleted && cachedRoute(managedKafka) == null;
        }
        log.tracef("Admin Server isDeleted = %s", isDeleted);
        return isDeleted;
    }

    @Override
    public String uri(ManagedKafka managedKafka) {
        return getRouteURI(cachedRoute(managedKafka));
    }

    String getRouteURI(Route route) {
        if (route != null) {
            RouteSpec spec = route.getSpec();
            StringBuilder uri = new StringBuilder();
            uri.append(spec.getTls() != null ? "https://" : "http://");
            uri.append(spec.getHost());

            return uri.toString();
        }

        return null;
    }

    private Route cachedRoute(ManagedKafka managedKafka) {
        return informerManager.getLocalRoute(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    private Resource<Route> adminRouteResource(ManagedKafka managedKafka) {
        return openShiftClient.routes()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerName(managedKafka));
    }
}
