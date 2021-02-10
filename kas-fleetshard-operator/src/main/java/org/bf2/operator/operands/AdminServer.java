package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
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
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
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

    // TODO: enable when Quarkus injection issue about KuberneteClient vs OpenShiftClient ambiguity will be fixed
    /*
    OpenShiftClient openShiftClient;

    void onStart(@Observes StartupEvent ev) {
        openShiftClient = kubernetesClient.adapt(OpenShiftClient.class);
    }
    */

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        Deployment deployment = deploymentFrom(managedKafka);
        // Admin Server deployment resource doesn't exist, has to be created
        if (kubernetesClient.apps().deployments()
                .inNamespace(deployment.getMetadata().getNamespace())
                .withName(deployment.getMetadata().getName()).get() == null) {
            kubernetesClient.apps().deployments().inNamespace(deployment.getMetadata().getNamespace()).create(deployment);
        // Admin Server deployment resource already exists, has to be updated
        } else {
            kubernetesClient.apps().deployments().inNamespace(deployment.getMetadata().getNamespace()).createOrReplace(deployment);
        }

        Service service = serviceFrom(managedKafka);
        // Admin Server service resource doesn't exist, has to be created
        if (kubernetesClient.services()
                .inNamespace(service.getMetadata().getNamespace())
                .withName(service.getMetadata().getName()).get() == null) {
            kubernetesClient.services().inNamespace(service.getMetadata().getNamespace()).create(service);
        // Admin Server service resource already exists, has to be updated
        } else {
            kubernetesClient.services().inNamespace(service.getMetadata().getNamespace()).createOrReplace(service);
        }

        // TODO: enable when Quarkus injection issue about KuberneteClient vs OpenShiftClient ambiguity will be fixed
        /*
        Route route = routeFrom(managedKafka);
        // Admin Server route resource doesn't exist, has to be created
        if (openShiftClient.routes()
                .inNamespace(route.getMetadata().getNamespace())
                .withName(route.getMetadata().getName()).get() == null) {
            openShiftClient.routes().inNamespace(route.getMetadata().getNamespace()).create(route);
        // Admin Server route resource already exists, has to be updated
        } else {
            openShiftClient.routes().inNamespace(route.getMetadata().getNamespace()).createOrReplace(route);
        }
        */
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

        // TODO: enable when Quarkus injection issue about KuberneteClient vs OpenShiftClient ambiguity will be fixed
        /*
        openShiftClient.routes()
                .inNamespace(adminServerNamespace(managedKafka))
                .withName(adminServerName(managedKafka))
                .delete();
         */
    }

    /* test */
    protected Deployment deploymentFrom(ManagedKafka managedKafka) {
        String adminServerName = adminServerName(managedKafka);

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName(adminServerName)
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withLabels(getLabels(adminServerName))
                .endMetadata()
                .withNewSpec()
                    .withNewSelector()
                        .addToMatchLabels(getLabels(adminServerName))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels(getLabels(adminServerName))
                        .endMetadata()
                        .withNewSpec()
                            .addNewContainer()
                                .withName(adminServerName)
                                .withImage("quay.io/sknot/strimzi-admin:0.0.3")
                                .withEnv(getEnvVar(managedKafka))
                                .withPorts(getContainerPorts())
                            .endContainer()
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

    private static Map<String, String> getLabels(String adminServerName) {
        Map<String, String> labels = new HashMap<>(2);
        labels.put("app", adminServerName);
        labels.put("app.kubernetes.io/managed-by", "kas-fleetshard-operator");
        return labels;
    }

    private static List<EnvVar> getEnvVar(ManagedKafka managedKafka) {
        List<EnvVar> envVars = new ArrayList<>(2);
        // TODO: move to port 9095 that should be OAuth enabled in the Kafka resource
        envVars.add(new EnvVarBuilder().withName("KAFKA_ADMIN_BOOTSTRAP_SERVERS").withValue(managedKafka.getMetadata().getName() + "-kafka-bootstrap:9092").build());
        return envVars;
    }

    private static List<ContainerPort> getContainerPorts() {
        return Collections.singletonList(new ContainerPortBuilder().withName("http").withContainerPort(8080).build());
    }

    private static List<ServicePort> getServicePorts() {
        return Collections.singletonList(new ServicePortBuilder().withName("http").withProtocol("TCP").withPort(8080).withTargetPort(new IntOrString("http")).build());
    }

    /* test */
    protected Service serviceFrom(ManagedKafka managedKafka) {
        String adminServerName = adminServerName(managedKafka);

        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    .withLabels(getLabels(adminServerName))
                .endMetadata()
                .withNewSpec()
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
    protected Route routeFrom(ManagedKafka managedKafka) {
        String adminServerName = adminServerName(managedKafka);

        Route route = new RouteBuilder()
                .withNewMetadata()
                    .withNamespace(adminServerNamespace(managedKafka))
                    .withName(adminServerName(managedKafka))
                    // TODO: adding labels for shared Ingress controller
                .endMetadata()
                .withNewSpec()
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

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        Deployment deployment = informerManager.getLocalDeployment(adminServerNamespace(managedKafka), adminServerName(managedKafka));
        boolean isInstalling = deployment == null || deployment.getStatus() == null;
        log.info("Admin Server isInstalling = {}", isInstalling);
        return isInstalling;
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        Deployment deployment = informerManager.getLocalDeployment(adminServerNamespace(managedKafka), adminServerName(managedKafka));
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

    public static String adminServerName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-admin-server";
    }

    public static String adminServerNamespace(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getNamespace();
    }
}
