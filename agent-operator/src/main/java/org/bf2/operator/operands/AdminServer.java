package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * Provides same functionalities to get a AdminServer deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
public class AdminServer {

    @Inject
    private KubernetesClient kubernetesClient;

    private Deployment deployment;
    private Service service;

    public void createOrUpdate(ManagedKafka managedKafka) {
        // TODO: createOrUpdate the AdminServer resources
    }

    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        // TODO: delete the AdminServer resources
    }

    public Deployment deploymentFrom(ManagedKafka managedKafka) {
        // TODO: generate the admin server deployment
        return new DeploymentBuilder().build();
    }

    public Service serviceFrom(ManagedKafka managedKafka) {
        // TODO: generated the admin server service
        return new ServiceBuilder().build();
    }

    public boolean isInstalling() {
        // TODO: logic for check if it's installing
        return false;
    }

    public boolean isReady() {
        // TODO: logic for check if it's ready
        return true;
    }

    public boolean isError() {
        // TODO: logic for check if it's error
        return false;
    }

    public Deployment getDeployment() {
        return deployment;
    }

    public void setDeployment(Deployment deployment) {
        this.deployment = deployment;
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }
}
