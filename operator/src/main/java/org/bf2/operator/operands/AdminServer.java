package org.bf2.operator.operands;

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
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
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
import io.fabric8.openshift.api.model.TLSConfig;
import io.fabric8.openshift.api.model.TLSConfigBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.Context;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.ConfigMapManager;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.managers.IngressControllerManager;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides same functionalities to get a AdminServer deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@Startup
@ApplicationScoped
@DefaultBean
public class AdminServer extends AbstractAdminServer {

    private static final String CONFIGMAP_ENVOY = "admin-server-envoy";

    private static final String VOLUME_CONFIG = "config";
    private static final String VOLUME_CONFIG_PATH = "/opt/kafka-admin-api/custom-config/";

    private static final String VOLUME_ENVOY_CONFIG = "config-envoy";
    private static final String VOLUME_ENVOY_CONFIG_PATH = "/configs/envoy";

    private static final String VOLUME_SOCKETS = "sockets";
    private static final String VOLUME_SOCKETS_PATH = "/sockets";

    private static final String VOLUME_TLS = "tls";
    private static final String VOLUME_TLS_PATH = "/secrets/tls";

    private static final int MANAGEMENT_PORT = 9990;
    private static final int ENVOY_ADMIN_PORT = 9000;
    private static final int ENVOY_INGRESS_PORT = 9001;

    private static final String MANAGEMENT_PORT_NAME = "management";
    private static final String ENVOY_ADMIN_PORT_NAME = "envoy-admin";
    private static final String ENVOY_INGRESS_PORT_NAME = "envoy-ingress";

    private static final IntOrString MANAGEMENT_PORT_TARGET = new IntOrString(MANAGEMENT_PORT_NAME);
    private static final IntOrString ENVOY_ADMIN_PORT_TARGET = new IntOrString(ENVOY_ADMIN_PORT_NAME);
    private static final IntOrString ENVOY_INGRESS_PORT_TARGET = new IntOrString(ENVOY_INGRESS_PORT_NAME);

    @Inject
    Logger log;

    @ConfigProperty(name = "image.admin-api")
    String adminApiImage;

    @ConfigProperty(name = "adminserver.cors.allowlist")
    Optional<String> corsAllowList;

    OpenShiftClient openShiftClient;

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    @Inject
    protected KafkaInstanceConfiguration config;

    @Inject
    protected Instance<IngressControllerManager> ingressControllerManagerInstance;

    @Inject
    protected ConfigMapManager configMapManager;

    void onStart(@Observes StartupEvent ev) {
        if (kubernetesClient.isAdaptable(OpenShiftClient.class)) {
            openShiftClient = kubernetesClient.adapt(OpenShiftClient.class);
        }
    }

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        super.createOrUpdate(managedKafka);

        configMapManager.createOrUpdate(managedKafka, CONFIGMAP_ENVOY, false);

        if (openShiftClient != null) {
            Route currentRoute = cachedRoute(managedKafka);
            Route route = routeFrom(managedKafka, currentRoute);

            OperandUtils.createOrUpdate(openShiftClient.routes(), route);
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        super.delete(managedKafka, context);
        configMapManager.delete(managedKafka, CONFIGMAP_ENVOY);

        if (openShiftClient != null) {
            adminRouteResource(managedKafka).delete();
        }
    }

    /* test */
    @Override
    public Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current) {
        String adminServerName = adminServerName(managedKafka);

        DeploymentBuilder builder = current != null ? new DeploymentBuilder(current) : new DeploymentBuilder();

        builder
                .editOrNewMetadata()
                    .withName(adminServerName)
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withLabels(buildLabels(adminServerName))
                .endMetadata()
                .editOrNewSpec()
                    .withReplicas(1)
                    .editOrNewSelector()
                        .withMatchLabels(buildSelectorLabels(adminServerName))
                    .endSelector()
                    .editOrNewTemplate()
                        .editOrNewMetadata()
                            .withLabels(buildLabels(adminServerName))
                        .endMetadata()
                        .editOrNewSpec()
                            .withVolumes(buildVolumes(managedKafka))
                            .withContainers(buildContainers(managedKafka))
                            .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                        .endSpec()
                    .endTemplate()
                .endSpec();


        if(this.config.getAdminserver().isColocateWithZookeeper()) {
            builder
                .editOrNewSpec()
                    .editOrNewTemplate()
                        .editOrNewSpec()
                        .withAffinity(OperandUtils.buildZookeeperPodAffinity(managedKafka))
                        .endSpec()
                    .endTemplate()
                .endSpec();
        }

        Deployment deployment = builder.build();

        // setting the ManagedKafka has owner of the Admin Server deployment resource is needed
        // by the operator sdk to handle events on the Deployment resource properly
        OperandUtils.setAsOwner(managedKafka, deployment);

        return deployment;
    }

    @Override
    public Service serviceFrom(ManagedKafka managedKafka, Service current) {
        String adminServerName = adminServerName(managedKafka);

        ServiceBuilder builder = current != null ? new ServiceBuilder(current) : new ServiceBuilder();
        Map<String, String> annotations;

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            annotations = Collections.emptyMap();
        } else {
            annotations = Map.of(
                    "service.alpha.openshift.io/serving-cert-secret-name",
                    adminServerName(managedKafka) + "-tls-secret");
        }

        Service service = builder
                .editOrNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    .withLabels(buildLabels(adminServerName))
                    .withAnnotations(annotations)
                .endMetadata()
                .editOrNewSpec()
                    .withClusterIP(null) // to prevent 422 errors
                    .withSelector(buildSelectorLabels(adminServerName))
                    .withPorts(buildEnvoyServicePorts())
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Admin Server service resource is needed
        // by the operator sdk to handle events on the Service resource properly
        OperandUtils.setAsOwner(managedKafka, service);

        return service;
    }

    /* test */
    protected Route routeFrom(ManagedKafka managedKafka, Route current) {
        RouteBuilder builder = current != null ? new RouteBuilder(current) : new RouteBuilder();

        final IntOrString targetPort = ENVOY_INGRESS_PORT_TARGET;
        final TLSConfig tlsConfig = new TLSConfigBuilder().withTermination("passthrough").build();

        Route route = builder
                .editOrNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    .withLabels(buildRouteLabels())
                .endMetadata()
                .editOrNewSpec()
                    .withNewTo()
                        .withKind("Service")
                        .withName(adminServerName(managedKafka))
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
        Probe adminServerLivenessProbe = buildProbe("/health/liveness", MANAGEMENT_PORT_TARGET, 5, 15, 1, 3);

        Container adminServerContainer = new ContainerBuilder()
                .withName("admin-server")
                .withImage(adminApiImage)
                .withEnv(buildEnvVar(managedKafka))
                .withPorts(buildAdminServerContainerPorts())
                .withResources(buildResources())
                .withReadinessProbe(adminServerLivenessProbe)
                .withLivenessProbe(adminServerLivenessProbe)
                .withVolumeMounts(
                        volumeMount(VOLUME_CONFIG, VOLUME_CONFIG_PATH),
                        volumeMount(VOLUME_SOCKETS, VOLUME_SOCKETS_PATH))
                .build();

        Probe envoyReadyProbe = buildProbe("/ready", ENVOY_ADMIN_PORT_TARGET, 1, 10, 1, 10);

        Container envoyContainer = new ContainerBuilder()
                .withName("envoy-sidecar")
                .withImage("envoyproxy/envoy:v1.20.1") // TODO: Parameterize envoy image reference
                .withCommand("envoy", "--config-path", "/configs/envoy/main.yaml")
                .withPorts(buildEnvoyContainerPorts())
                .withResources(buildResources()) // FIXME: separate resources for sidecar
                .withReadinessProbe(envoyReadyProbe)
                .withLivenessProbe(envoyReadyProbe)
                .withVolumeMounts(
                        volumeMount(VOLUME_ENVOY_CONFIG, VOLUME_ENVOY_CONFIG_PATH),
                        volumeMount(VOLUME_SOCKETS, VOLUME_SOCKETS_PATH),
                        volumeMount(VOLUME_TLS, VOLUME_TLS_PATH))
                .build();

        return List.of(adminServerContainer, envoyContainer);
    }

    private Probe buildProbe(String path, IntOrString port, int timeout, int initialDelay, int successThreshold, int failureThreshold) {
        return new ProbeBuilder()
                .withHttpGet(
                        new HTTPGetActionBuilder()
                        .withPath(path)
                        .withPort(port)
                        .build()
                )
                .withTimeoutSeconds(timeout)
                .withInitialDelaySeconds(initialDelay)
                .withSuccessThreshold(successThreshold)
                .withFailureThreshold(failureThreshold)
                .build();
    }

    private List<Volume> buildVolumes(ManagedKafka managedKafka) {
        Volume configVolume = new VolumeBuilder()
                .withName(VOLUME_CONFIG)
                .editOrNewConfigMap()
                    .withName(configMapManager.getFullName(managedKafka, "admin-server"))
                    .withOptional(Boolean.TRUE)
                .endConfigMap()
                .build();

        Volume envoyConfigVolume = new VolumeBuilder()
                .withName(VOLUME_ENVOY_CONFIG)
                .editOrNewConfigMap()
                    .withName(configMapManager.getFullName(managedKafka, CONFIGMAP_ENVOY))
                .endConfigMap()
                .build();

        Volume socketsVolume = new VolumeBuilder()
                .withName(VOLUME_SOCKETS)
                .editOrNewEmptyDir()
                    .withMedium("Memory")
                .endEmptyDir()
                .build();

        String tlsSecretName;

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            tlsSecretName = SecuritySecretManager.kafkaTlsSecretName(managedKafka);
        } else {
            tlsSecretName = adminServerName(managedKafka) + "-tls-secret";
        }

        Volume tlsVolume = new VolumeBuilder()
                .withName(VOLUME_TLS)
                .editOrNewSecret()
                    .withSecretName(tlsSecretName)
                .endSecret()
                .build();

        return List.of(configVolume, envoyConfigVolume, socketsVolume, tlsVolume);
    }

    private VolumeMount volumeMount(String name, String mountPath) {
        return new VolumeMountBuilder().withName(name).withMountPath(mountPath).build();
    }

    private Map<String, String> buildSelectorLabels(String adminServerName) {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        labels.put("app", adminServerName);
        return labels;
    }

    private Map<String, String> buildLabels(String adminServerName) {
        Map<String, String> labels = buildSelectorLabels(adminServerName);
        labels.put("app.kubernetes.io/component", "adminserver");
        return labels;
    }

    private Map<String, String> buildRouteLabels() {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        labels.put("ingressType", "sharded");

        if (ingressControllerManagerInstance.isResolvable()) {
            labels.putAll(ingressControllerManagerInstance.get().getRouteMatchLabels());
        }
        return labels;
    }

    private List<EnvVar> buildEnvVar(ManagedKafka managedKafka) {
        List<EnvVar> envVars = new ArrayList<>();

        addEnvVar(envVars, "KAFKA_ADMIN_BOOTSTRAP_SERVERS", managedKafka.getMetadata().getName() + "-kafka-bootstrap:9095");
        addEnvVar(envVars, "KAFKA_ADMIN_BROKER_TLS_ENABLED", "true");
        addEnvVar(envVars, "KAFKA_ADMIN_DOMAIN_SOCKET", "/sockets/api.socket");
        addEnvVarSecret(envVars, "KAFKA_ADMIN_BROKER_TRUSTED_CERT", SecuritySecretManager.strimziClusterCaCertSecret(managedKafka), "ca.crt");
        addEnvVar(envVars, "KAFKA_ADMIN_ACL_RESOURCE_OPERATIONS", this.config.getKafka().getAcl().getResourceOperations());

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

        if (corsAllowList.isPresent()) {
            addEnvVar(envVars, "CORS_ALLOW_LIST_REGEX", corsAllowList.get());
        }

        return envVars;
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

    private List<ContainerPort> buildAdminServerContainerPorts() {
        return List.of(new ContainerPortBuilder()
                           .withName(MANAGEMENT_PORT_NAME)
                           .withContainerPort(MANAGEMENT_PORT)
                           .build());
    }

    private List<ContainerPort> buildEnvoyContainerPorts() {
        return List.of(new ContainerPortBuilder()
                            .withName(ENVOY_ADMIN_PORT_NAME)
                            .withProtocol("TCP")
                            .withContainerPort(ENVOY_ADMIN_PORT)
                            .build(),
                        new ContainerPortBuilder()
                            .withName(ENVOY_INGRESS_PORT_NAME)
                            .withProtocol("TCP")
                            .withContainerPort(ENVOY_INGRESS_PORT)
                            .build());
    }

    private List<ServicePort> buildEnvoyServicePorts() {
        return Collections.singletonList(new ServicePortBuilder()
                           .withName(ENVOY_INGRESS_PORT_NAME)
                           .withProtocol("TCP")
                           .withPort(ENVOY_INGRESS_PORT)
                           .withTargetPort(ENVOY_INGRESS_PORT_TARGET)
                           .build());
    }

    private ResourceRequirements buildResources() {
        Quantity mem = new Quantity(config.getAdminserver().getContainerMemory());
        Quantity cpu = new Quantity(config.getAdminserver().getContainerCpu());
        return new ResourceRequirementsBuilder()
                .addToRequests("memory", mem)
                .addToRequests("cpu", cpu)
                .addToLimits("memory", mem)
                .addToLimits("cpu", cpu)
                .build();
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        boolean isDeleted = super.isDeleted(managedKafka) &&
                configMapManager.allDeleted(managedKafka, CONFIGMAP_ENVOY);

        if (openShiftClient != null) {
            isDeleted = isDeleted && cachedRoute(managedKafka) == null;
        }

        log.tracef("Admin Server isDeleted = %s", isDeleted);
        return isDeleted;
    }

    @Override
    public String uri(ManagedKafka managedKafka) {
        Route route = cachedRoute(managedKafka);
        return route != null ? route.getSpec().getHost() : null;
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
