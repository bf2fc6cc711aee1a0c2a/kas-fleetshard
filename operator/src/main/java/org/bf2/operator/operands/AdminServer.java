package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Parameter;
import io.fabric8.openshift.api.model.ParameterBuilder;
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
import java.util.stream.Collectors;

/**
 * Provides same functionalities to get a AdminServer deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@Startup
@ApplicationScoped
@DefaultBean
public class AdminServer extends AbstractAdminServer {

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

    @ConfigProperty(name = "adminserver.cors.allowlist")
    Optional<String> corsAllowList;

    OpenShiftClient openShiftClient;

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    @Inject
    protected KafkaInstanceConfiguration config;

    @Inject
    protected Instance<IngressControllerManager> ingressControllerManagerInstance;

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
    public Deployment deploymentFrom(ManagedKafka managedKafka, ConfigMap companionTemplates) {
        Deployment current = cachedDeployment(managedKafka);
        Deployment deployment = Operand.deploymentFromTemplate(companionTemplates, "admin-server-template", adminServerName(managedKafka), buildParameters(managedKafka), current);

        templateValidationWorkaround(deployment, managedKafka);
        // setting the ManagedKafka has owner of the Admin Server deployment resource is needed
        // by the operator sdk to handle events on the Deployment resource properly
        OperandUtils.setAsOwner(managedKafka, deployment);

        if(this.config.getAdminserver().isColocateWithZookeeper()) {
            builder
                    .editOrNewSpec()
                        .editOrNewTemplate()
                            .editOrNewSpec()
                                .withAffinity(OperandUtils.buildZookeeperPodAffinity(managedKafka))
                            .endSpec()
                        .endTemplate()
                    .endSpec();
        return deployment;
    }

    private void templateValidationWorkaround(Deployment deployment, ManagedKafka managedKafka) {
        // we need to add Deployment parameters which cause validation failures while unmarshalling the template + conditional parameters do not seem to be supported
        // https://github.com/fabric8io/kubernetes-client/issues/3460

        deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(envVarFromSecret("KAFKA_ADMIN_BROKER_TRUSTED_CERT", SecuritySecretManager.strimziClusterCaCertSecret(managedKafka), "ca.crt"));
        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getPorts().add(new ContainerPortBuilder().withName(HTTPS_PORT_NAME).withContainerPort(HTTPS_PORT).build());
        } else {
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getPorts().add(new ContainerPortBuilder().withName(HTTP_PORT_NAME).withContainerPort(HTTP_PORT).build());
        }

        // list
        deployment.getSpec().getTemplate().getSpec().setImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka));

        // quotes in the value
        deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(new EnvVarBuilder().withName("KAFKA_ADMIN_ACL_RESOURCE_OPERATIONS").withValue(this.config.getKafka().getAcl().getResourceOperations()).build());

        // conditional envar (value cannot be omitted)
        if (SecuritySecretManager.isKafkaAuthenticationEnabled(managedKafka)) {
            ManagedKafkaAuthenticationOAuth oauth = managedKafka.getSpec().getOauth();
            if (oauth.getTlsTrustedCertificate() != null) {
                deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(envVarFromSecret("KAFKA_ADMIN_OAUTH_TRUSTED_CERT", SecuritySecretManager.ssoTlsSecretName(managedKafka), "keycloak.crt"));
            }
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(new EnvVarBuilder().withName("KAFKA_ADMIN_OAUTH_JWKS_ENDPOINT_URI").withValue(oauth.getJwksEndpointURI()).build());
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(new EnvVarBuilder().withName("KAFKA_ADMIN_OAUTH_VALID_ISSUER_URI").withValue(oauth.getValidIssuerEndpointURI()).build());
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(new EnvVarBuilder().withName("KAFKA_ADMIN_OAUTH_TOKEN_ENDPOINT_URI").withValue(oauth.getTokenEndpointURI()).build());
        }

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(envVarFromSecret("KAFKA_ADMIN_TLS_CERT", SecuritySecretManager.kafkaTlsSecretName(managedKafka), "tls.crt"));
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().add(envVarFromSecret("KAFKA_ADMIN_TLS_KEY", SecuritySecretManager.kafkaTlsSecretName(managedKafka), "tls.key"));
        }
    }

    private Map<String, String> buildParameters(ManagedKafka managedKafka) {
        List<Parameter> parameters = new ArrayList<>();

        addParameter(parameters, "KAFKA_ADMIN_SERVER_APP", adminServerName(managedKafka));
        addParameter(parameters, "KAFKA_ADMIN_SERVER_DEPLOYMENT", adminServerName(managedKafka));
        addParameter(parameters, "KAFKA_ADMIN_NAMESPACE", managedKafka.getMetadata().getNamespace());
        addParameter(parameters, "KAFKA_ADMIN_BOOTSTRAP_SERVERS", managedKafka.getMetadata().getName() + "-kafka-bootstrap:9095");

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            addParameter(parameters, "KAFKA_ADMIN_TLS_VERSION", "TLSv1.3,TLSv1.2");
        } else {
            addParameter(parameters, "KAFKA_ADMIN_TLS_VERSION", "TLSv1.3");
        }

        if (SecuritySecretManager.isKafkaAuthenticationEnabled(managedKafka)) {
            addParameter(parameters, "KAFKA_ADMIN_OAUTH_ENABLED", "true");

        } else {
            addParameter(parameters, "KAFKA_ADMIN_OAUTH_ENABLED", "false");
        }

        if (corsAllowList.isPresent()) {
            addParameter(parameters, "KAFKA_ADMIN_CORS_ALLOW_LIST_REGEX", corsAllowList.get());
        } else {
            addParameter(parameters, "KAFKA_ADMIN_CORS_ALLOW_LIST_REGEX", "(https?:\\/\\/localhost(:\\d*)?)");
        }

        if (SecuritySecretManager.isKafkaExternalCertificateEnabled(managedKafka)) {
            addParameter(parameters, "KAFKA_ADMIN_API_PORT_NAME", HTTPS_PORT_NAME);
            addParameter(parameters, "KAFKA_ADMIN_API_PORT", Integer.toString(HTTPS_PORT));
        } else {
            addParameter(parameters, "KAFKA_ADMIN_API_PORT_NAME", HTTP_PORT_NAME);
            addParameter(parameters, "KAFKA_ADMIN_API_PORT", Integer.toString(HTTP_PORT));
        }

        addParameter(parameters, "KAFKA_ADMIN_IMAGE_PULL_SECRETS", "[" + imagePullSecretManager.getOperatorImagePullSecrets(managedKafka).stream().map(ref -> ref.getName()).collect(Collectors.joining(", ")) + "]");

        addParameter(parameters, "KAFKA_ADMIN_VOLUME_NAME", managedKafka.getMetadata().getName() + "-tls-ca-cert");
        addParameter(parameters, "KAFKA_ADMIN_VOLUME_SECRET", managedKafka.getMetadata().getName() + "-cluster-ca-cert");

        return parameters.stream().collect(Collectors.toMap(entry -> entry.getName(), entry -> entry.getValue() == null ? "\"\"" :  entry.getValue()));
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

    private void addParameter(List<Parameter> parameters, String name, String value) {
        parameters.add(new ParameterBuilder().withName(name).withValue(value).build());
    }

    private EnvVar envVarFromSecret(String envVarName, String secretName, String secretKey) {
        return new EnvVarBuilder()
                .withName(envVarName)
                .withNewValueFrom()
                .withNewSecretKeyRef(secretKey, secretName, false)
                .endValueFrom()
                .build();
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
