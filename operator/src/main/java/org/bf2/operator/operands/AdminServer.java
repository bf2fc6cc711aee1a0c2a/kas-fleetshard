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
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.Context;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.StartupEvent;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import java.time.Duration;
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

    /**
     * OpenShift route attribute for the configuration of HTTP strict transport security.
     *
     * @see <a href="https://docs.openshift.com/container-platform/4.7/networking/routes/route-configuration.html#nw-enabling-hsts_route-configuration">
     *  Enabling HTTP strict transport security</a>
     */
    private static final String ROUTE_STRICT_TRANSPORT_SECURITY = "haproxy.router.openshift.io/hsts_header";

    @Inject
    Logger log;

    @ConfigProperty(name = "kafka.external.certificate.enabled", defaultValue = "false")
    boolean isKafkaExternalCertificateEnabled;

    @ConfigProperty(name = "image.admin-api")
    String adminApiImage;

    @ConfigProperty(name = "adminserver.cors.allowlist")
    Optional<String> corsAllowList;

    OpenShiftClient openShiftClient;

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
            openShiftClient.routes().inNamespace(route.getMetadata().getNamespace()).createOrReplace(route);
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
                    .withLabels(getLabels(adminServerName))
                .endMetadata()
                .editOrNewSpec()
                    .withReplicas(1)
                    .editOrNewSelector()
                        .withMatchLabels(getSelectorLabels(adminServerName))
                    .endSelector()
                    .editOrNewTemplate()
                        .editOrNewMetadata()
                            .withLabels(getLabels(adminServerName))
                        .endMetadata()
                        .editOrNewSpec()
                            .withContainers(getContainers(managedKafka))
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
                    .withLabels(getLabels(adminServerName))
                .endMetadata()
                .editOrNewSpec()
                    .withSelector(getSelectorLabels(adminServerName))
                    .withPorts(getServicePorts())
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

        String tlsCertificate = null;
        String tlsKey = null;
        Map<String, String> annotations = Map.of(ROUTE_STRICT_TRANSPORT_SECURITY,
                String.format("max-age=%d", Duration.ofDays(365).toSeconds()));

        if (isKafkaExternalCertificateEnabled) {
            tlsCertificate = managedKafka.getSpec().getEndpoint().getTls().getCert();
            tlsKey = managedKafka.getSpec().getEndpoint().getTls().getKey();
        }

        Route route = builder
                .editOrNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    .withLabels(getRouteLabels())
                    .withAnnotations(annotations)
                .endMetadata()
                .editOrNewSpec()
                    .withNewTo()
                        .withKind("Service")
                        .withName(adminServerName)
                    .endTo()
                    .withNewPort()
                        .withTargetPort(new IntOrString("http"))
                    .endPort()
                    .withHost("admin-server-" + managedKafka.getSpec().getEndpoint().getBootstrapServerHost())
                    .withNewTls()
                        .withTermination("edge")
                        .withCertificate(tlsCertificate)
                        .withKey(tlsKey)
                    .endTls()
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Admin Server route resource is needed
        // by the operator sdk to handle events on the Route resource properly
        OperandUtils.setAsOwner(managedKafka, route);

        return route;
    }

    private List<Container> getContainers(ManagedKafka managedKafka) {
        Container container = new ContainerBuilder()
                .withName("admin-server")
                .withImage(adminApiImage)
                .withEnv(getEnvVar(managedKafka))
                .withPorts(getContainerPorts())
                .withResources(getResources())
                .withReadinessProbe(getProbe())
                .withLivenessProbe(getProbe())
                .build();

        return Collections.singletonList(container);
    }

    private Probe getProbe() {
        return new ProbeBuilder()
                .withHttpGet(
                        new HTTPGetActionBuilder()
                        .withPath("/health/liveness")
                        .withPort(new IntOrString(8080))
                        .build()
                )
                .withTimeoutSeconds(5)
                .withInitialDelaySeconds(15)
                .build();
    }

    private Map<String, String> getSelectorLabels(String adminServerName) {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        labels.put("app", adminServerName);
        return labels;
    }

    private Map<String, String> getLabels(String adminServerName) {
        Map<String, String> labels = getSelectorLabels(adminServerName);
        labels.put("app.kubernetes.io/component", "adminserver");
        return labels;
    }

    private Map<String, String> getRouteLabels() {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        labels.put("ingressType", "sharded");
        return labels;
    }

    private List<EnvVar> getEnvVar(ManagedKafka managedKafka) {
        List<EnvVar> envVars = new ArrayList<>(1);
        envVars.add(new EnvVarBuilder().withName("KAFKA_ADMIN_BOOTSTRAP_SERVERS").withValue(managedKafka.getMetadata().getName() + "-kafka-bootstrap:9095").build());
        if (corsAllowList.isPresent()) {
            envVars.add(new EnvVarBuilder().withName("CORS_ALLOW_LIST_REGEX").withValue(corsAllowList.get()).build());
        }
        return envVars;
    }

    private List<ContainerPort> getContainerPorts() {
        return Collections.singletonList(new ContainerPortBuilder().withName("http").withContainerPort(8080).build());
    }

    private List<ServicePort> getServicePorts() {
        return Collections.singletonList(new ServicePortBuilder().withName("http").withProtocol("TCP").withPort(8080).withTargetPort(new IntOrString("http")).build());
    }

    private ResourceRequirements getResources() {
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
