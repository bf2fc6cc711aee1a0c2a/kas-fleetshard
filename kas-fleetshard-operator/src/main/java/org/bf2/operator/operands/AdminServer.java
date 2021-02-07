package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * Provides same functionalities to get a AdminServer deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
public class AdminServer implements Operand<ManagedKafka> {

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    InformerManager informerManager;

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        // TODO: createOrUpdate the AdminServer resources
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        // TODO: delete the AdminServer resources
    }

    /* test */
    protected Deployment deploymentFrom(ManagedKafka managedKafka) {
        // TODO: generate the admin server deployment
        return new DeploymentBuilder().build();
    }

    public Service serviceFrom(ManagedKafka managedKafka) {
        // TODO: generated the admin server service
        return new ServiceBuilder().build();
    }

    @Override
    public boolean isInstalling(ManagedKafka managedKafka) {
        // TODO: logic for check if it's installing
        return false;
    }

    @Override
    public boolean isReady(ManagedKafka managedKafka) {
        // TODO: logic for check if it's ready
        return true;
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
