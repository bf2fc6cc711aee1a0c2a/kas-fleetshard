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
import io.quarkus.runtime.StartupEvent;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuth;
import org.bf2.operator.secrets.ImagePullSecretManager;
import org.bf2.operator.secrets.SecuritySecretManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
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
@ApplicationScoped
@DefaultBean
public class AdminServer extends AbstractAdminServer {

    private static final Quantity CONTAINER_MEMORY_REQUEST = new Quantity("256Mi");
    private static final Quantity CONTAINER_CPU_REQUEST = new Quantity("250m");
    private static final Quantity CONTAINER_MEMORY_LIMIT = new Quantity("512Mi");
    private static final Quantity CONTAINER_CPU_LIMIT = new Quantity("500m");

    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    private static final int MANAGEMENT_PORT = 9990;

    private static final String HTTP_PORT_NAME = "http";
    private static final String HTTPS_PORT_NAME = "https";
    private static final String MANAGEMENT_PORT_NAME = "management";

    private static final IntOrString HTTP_PORT_TARGET = new IntOrString(HTTP_PORT_NAME);
    private static final IntOrString HTTPS_PORT_TARGET = new IntOrString(HTTPS_PORT_NAME);
    private static final IntOrString MANAGEMENT_PORT_TARGET = new IntOrString(MANAGEMENT_PORT_NAME);

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

    void onStart(@Observes StartupEvent ev) {
        if (kubernetesClient.isAdaptable(OpenShiftClient.class)) {
            openShiftClient = kubernetesClient.adapt(OpenShiftClient.class);
        }
    }

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        super.createOrUpdate(managedKafka);

        if (openShiftClient != null) {
            Route currentRoute = cachedRoute(managedKafka);
            Route route = routeFrom(managedKafka, currentRoute);

            OperandUtils.createOrUpdate(openShiftClient.routes(), route);
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        super.delete(managedKafka, context);

        if (openShiftClient != null) {
            adminRouteResource(managedKafka).delete();
        }
    }

    /* test */
    @Override
    protected Deployment deploymentFrom(ManagedKafka managedKafka, Deployment current) {
        String adminServerName = adminServerName(managedKafka);

        DeploymentBuilder builder = current != null ? new DeploymentBuilder(current) : new DeploymentBuilder();

        Deployment deployment = builder
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
                            .withContainers(buildContainers(managedKafka))
                            .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                            .withVolumes(buildVolumes(managedKafka))
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Admin Server deployment resource is needed
        // by the operator sdk to handle events on the Deployment resource properly
        OperandUtils.setAsOwner(managedKafka, deployment);

        return deployment;
    }

    /* test */
    @Override
    protected Service serviceFrom(ManagedKafka managedKafka, Service current) {
        String adminServerName = adminServerName(managedKafka);

        ServiceBuilder builder = current != null ? new ServiceBuilder(current) : new ServiceBuilder();

        Service service = builder
                .editOrNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    .withLabels(buildLabels(adminServerName))
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

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            targetPort = HTTPS_PORT_TARGET;
            tlsConfig = new TLSConfigBuilder().withTermination("passthrough").build();
        } else {
            targetPort = HTTP_PORT_TARGET;
            tlsConfig = null;
        }

        Route route = builder
                .editOrNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    .withLabels(buildRouteLabels())
                .endMetadata()
                .editOrNewSpec()
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
        Container container = new ContainerBuilder()
                .withName("admin-server")
                .withImage(adminApiImage)
                .withEnv(buildEnvVar(managedKafka))
                .withPorts(buildContainerPorts(managedKafka))
                .withResources(buildResources())
                .withReadinessProbe(buildProbe())
                .withLivenessProbe(buildProbe())
                .withVolumeMounts(buildVolumeMounts(managedKafka))
                .build();

        return Collections.singletonList(container);
    }

    private Probe buildProbe() {
        return new ProbeBuilder()
                .withHttpGet(
                        new HTTPGetActionBuilder()
                        .withPath("/health/liveness")
                        .withPort(MANAGEMENT_PORT_TARGET)
                        .build()
                )
                .withTimeoutSeconds(5)
                .withInitialDelaySeconds(15)
                .build();
    }

    private List<VolumeMount> buildVolumeMounts(ManagedKafka managedKafka) {
        return Collections.singletonList(new VolumeMountBuilder()
                    .withName(adminServerConfigVolumeName(managedKafka))
                    /* Matches location expected by kafka-admin-api container. */
                    .withMountPath("/opt/kafka-admin-api/custom-config/")
                .build());
    }

    private List<Volume> buildVolumes(ManagedKafka managedKafka) {
        return Collections.singletonList(new VolumeBuilder()
                .withName(adminServerConfigVolumeName(managedKafka))
                .editOrNewConfigMap()
                    .withName(adminServerName(managedKafka))
                    .withOptional(Boolean.TRUE)
                .endConfigMap()
                .build());
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
        return labels;
    }

    private List<EnvVar> buildEnvVar(ManagedKafka managedKafka) {
        List<EnvVar> envVars = new ArrayList<>();

        addEnvVar(envVars, "KAFKA_ADMIN_BOOTSTRAP_SERVERS", managedKafka.getMetadata().getName() + "-kafka-bootstrap:9095");

        if (this.config.getKafka().getAcl().isCustomEnabled(managedKafka.getSpec().getOwners())) {
            addEnvVar(envVars, "KAFKA_ADMIN_ACL_RESOURCE_OPERATIONS", this.config.getKafka().getAcl().getResourceOperations());
        }

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            addEnvVarSecret(envVars, "KAFKA_ADMIN_TLS_CERT", SecuritySecretManager.kafkaTlsSecretName(managedKafka), "tls.crt");
            addEnvVarSecret(envVars, "KAFKA_ADMIN_TLS_KEY", SecuritySecretManager.kafkaTlsSecretName(managedKafka), "tls.key");
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

    private List<ContainerPort> buildContainerPorts(ManagedKafka managedKafka) {
        final String apiPortName;
        final int apiContainerPort;

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            apiPortName = HTTPS_PORT_NAME;
            apiContainerPort = HTTPS_PORT;
        } else {
            apiPortName = HTTP_PORT_NAME;
            apiContainerPort = HTTP_PORT;
        }

        return List.of(new ContainerPortBuilder()
                           .withName(apiPortName)
                           .withContainerPort(apiContainerPort)
                           .build(),
                       new ContainerPortBuilder()
                           .withName(MANAGEMENT_PORT_NAME)
                           .withContainerPort(MANAGEMENT_PORT)
                           .build());
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

    private ResourceRequirements buildResources() {
        ResourceRequirements resources = new ResourceRequirementsBuilder()
                .addToRequests("memory", CONTAINER_MEMORY_REQUEST)
                .addToRequests("cpu", CONTAINER_CPU_REQUEST)
                .addToLimits("memory", CONTAINER_MEMORY_LIMIT)
                .addToLimits("cpu", CONTAINER_CPU_LIMIT)
                .build();
        return resources;
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
