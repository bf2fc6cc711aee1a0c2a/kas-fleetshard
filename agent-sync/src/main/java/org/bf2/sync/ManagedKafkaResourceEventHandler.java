package org.bf2.sync;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.fabric8.kubernetes.client.informers.ResourceEventHandler;

@ApplicationScoped
final class ManagedKafkaResourceEventHandler implements ResourceEventHandler<ManagedKafka> {

    // TODO: where should this be coming from
    @ConfigProperty(name = "cluster.id")
    String id;

    @Inject
    @RestClient
    ControlPlaneRestClient controlPlane;

    @Override
    public void onUpdate(ManagedKafka oldObj, ManagedKafka newObj) {
        // an update will also be generated for each resyncPeriodInMillis
        // TODO: filter unnecessary updates
        controlPlane.updateKafkaClusterStatus(newObj.getStatus(), id, newObj.getKafkaClusterId());
    }

    @Override
    public void onDelete(ManagedKafka obj, boolean deletedFinalStateUnknown) {
        // TODO: this will depend upon the delete strategy chosen
        // Assuming that delete is managed by a status update, there is nothing to do
        // here
    }

    @Override
    public void onAdd(ManagedKafka obj) {
        // TODO: on a restart we'll hit add again for each resource - that could be
        // filtered
        if (obj.getStatus() != null) {
            controlPlane.updateKafkaClusterStatus(obj.getStatus(), id, obj.getKafkaClusterId());
        }
    }
}