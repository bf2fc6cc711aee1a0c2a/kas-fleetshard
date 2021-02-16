package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.Context;
import io.quarkus.runtime.StartupEvent;
import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides same functionalities to get a AdminServer deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
public class AdminServer implements Operand<ManagedKafka> {

    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    InformerManager informerManager;

    OpenShiftClient openShiftClient;

    void onStart(@Observes StartupEvent ev) {
        if (kubernetesClient.isAdaptable(OpenShiftClient.class)) {
            openShiftClient = kubernetesClient.adapt(OpenShiftClient.class);
        }
    }

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        Deployment currentDeployment = cachedDeployment(managedKafka);
        Deployment deployment = deploymentFrom(managedKafka, currentDeployment);
        // Admin Server deployment resource doesn't exist, has to be created
        if (kubernetesClient.apps().deployments()
                .inNamespace(deployment.getMetadata().getNamespace())
                .withName(deployment.getMetadata().getName()).get() == null) {
            kubernetesClient.apps().deployments().inNamespace(deployment.getMetadata().getNamespace()).create(deployment);
        // Admin Server deployment resource already exists, has to be updated
        } else {
            kubernetesClient.apps().deployments()
                    .inNamespace(deployment.getMetadata().getNamespace())
                    .withName(deployment.getMetadata().getName())
                    .patch(deployment);
        }

        Service currentService = cachedService(managedKafka);
        Service service = serviceFrom(managedKafka, currentService);
        // Admin Server service resource doesn't exist, has to be created
        if (kubernetesClient.services()
                .inNamespace(service.getMetadata().getNamespace())
                .withName(service.getMetadata().getName()).get() == null) {
            kubernetesClient.services().inNamespace(service.getMetadata().getNamespace()).create(service);
        // Admin Server service resource already exists, has to be updated
        } else {
            kubernetesClient.services()
                    .inNamespace(service.getMetadata().getNamespace())
                    .withName(service.getMetadata().getName())
                    .patch(service);
        }

        if (openShiftClient != null) {
            Route currentRoute = cachedRoute(managedKafka);
            Route route = routeFrom(managedKafka, currentRoute);
            // Admin Server route resource doesn't exist, has to be created
            if (openShiftClient.routes()
                    .inNamespace(route.getMetadata().getNamespace())
                    .withName(route.getMetadata().getName()).get() == null) {
                openShiftClient.routes().inNamespace(route.getMetadata().getNamespace()).create(route);
                // Admin Server route resource already exists, has to be updated
            } else {
                openShiftClient.routes()
                        .inNamespace(route.getMetadata().getNamespace())
                        .withName(route.getMetadata().getName())
                        .patch(route);
            }
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kubernetesClient.apps()
                .deployments()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerName(managedKafka))
                .delete();

        kubernetesClient.services()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerName(managedKafka))
                .delete();

        if (openShiftClient != null) {
            openShiftClient.routes()
                    .inNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    .delete();
        }
    }

    /* test */
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
                        .withMatchLabels(getLabels(adminServerName))
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
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(managedKafka.getApiVersion())
                .withKind(managedKafka.getKind())
                .withName(managedKafka.getMetadata().getName())
                .withUid(managedKafka.getMetadata().getUid())
                .build();
        deployment.getMetadata().setOwnerReferences(Collections.singletonList(ownerReference));

        return deployment;
    }

    /* test */
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
                    .withSelector(getLabels(adminServerName))
                    .withPorts(getServicePorts())
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Admin Server service resource is needed
        // by the operator sdk to handle events on the Service resource properly
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(managedKafka.getApiVersion())
                .withKind(managedKafka.getKind())
                .withName(managedKafka.getMetadata().getName())
                .withUid(managedKafka.getMetadata().getUid())
                .build();
        service.getMetadata().setOwnerReferences(Collections.singletonList(ownerReference));

        return service;
    }

    /* test */
    protected Route routeFrom(ManagedKafka managedKafka, Route current) {
        String adminServerName = adminServerName(managedKafka);

        RouteBuilder builder = current != null ? new RouteBuilder(current) : new RouteBuilder();

        Route route = builder
                .editOrNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    // TODO: adding labels for shared Ingress controller
                    .withLabels(getLabels(adminServerName))
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
                    .endTls()
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Admin Server route resource is needed
        // by the operator sdk to handle events on the Route resource properly
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(managedKafka.getApiVersion())
                .withKind(managedKafka.getKind())
                .withName(managedKafka.getMetadata().getName())
                .withUid(managedKafka.getMetadata().getUid())
                .build();
        route.getMetadata().setOwnerReferences(Collections.singletonList(ownerReference));

        return route;
    }

    private List<Container> getContainers(ManagedKafka managedKafka) {
        String adminServerName = adminServerName(managedKafka);

        Container container = new ContainerBuilder()
                .withName(adminServerName)
                .withImage("quay.io/sknot/strimzi-admin:0.0.3")
                .withEnv(getEnvVar(managedKafka))
                .withPorts(getContainerPorts())
                .build();

        return Collections.singletonList(container);
    }

    private Map<String, String> getLabels(String adminServerName) {
        Map<String, String> labels = new HashMap<>(2);
        labels.put("app", adminServerName);
        labels.put("app.kubernetes.io/managed-by", "kas-fleetshard-operator");
        return labels;
    }

    private List<EnvVar> getEnvVar(ManagedKafka managedKafka) {
        List<EnvVar> envVars = new ArrayList<>(2);
        // TODO: move to port 9095 that should be OAuth enabled in the Kafka resource
        envVars.add(new EnvVarBuilder().withName("KAFKA_ADMIN_BOOTSTRAP_SERVERS").withValue(managedKafka.getMetadata().getName() + "-kafka-bootstrap:9092").build());
        return envVars;
    }

    private List<ContainerPort> getContainerPorts() {
        return Collections.singletonList(new ContainerPortBuilder().withName("http").withContainerPort(8080).build());
    }

    private List<ServicePort> getServicePorts() {
        return Collections.singletonList(new ServicePortBuilder().withName("http").withProtocol("TCP").withPort(8080).withTargetPort(new IntOrString("http")).build());
    }

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        Deployment deployment = cachedDeployment(managedKafka);
        boolean isInstalling = deployment == null || deployment.getStatus() == null;
        log.info("Admin Server isInstalling = {}", isInstalling);
        return isInstalling;
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        Deployment deployment = cachedDeployment(managedKafka);
        boolean isReady = deployment != null && (deployment.getStatus() == null ||
                (deployment.getStatus().getReadyReplicas() != null && deployment.getStatus().getReadyReplicas().equals(deployment.getSpec().getReplicas())));
        log.info("Admin Server isReady = {}", isReady);
        return isReady;
    }

    @Override
    public boolean isError(ManagedKafka managedKafka) {
        // TODO: logic for check if it's error
        return false;
    }

    private Deployment cachedDeployment(ManagedKafka managedKafka) {
        return informerManager.getLocalDeployment(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    private Service cachedService(ManagedKafka managedKafka) {
        return informerManager.getLocalService(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    private Route cachedRoute(ManagedKafka managedKafka) {
        return informerManager.getLocalRoute(adminServerNamespace(managedKafka), adminServerName(managedKafka));
    }

    public String Uri(ManagedKafka managedKafka) {
        Route route = cachedRoute(managedKafka);
        return route != null ? route.getSpec().getHost() : null;
    }

    public static String adminServerName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-admin-server";
    }

    public static String adminServerNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }
}
