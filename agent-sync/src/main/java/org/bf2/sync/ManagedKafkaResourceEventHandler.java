package org.bf2.sync;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import io.fabric8.kubernetes.client.informers.ResourceEventHandler;

@ApplicationScoped
final class ManagedKafkaResourceEventHandler implements ResourceEventHandler<ManagedKafka> {

    @Inject
    ScopedControlPlanRestClient controlPlane;

    @Override
    public void onUpdate(ManagedKafka oldObj, ManagedKafka newObj) {
        // an update will also be generated for each resyncPeriodInMillis
        // TODO: filter unnecessary updates
        if (newObj.getStatus() != null) {
            controlPlane.updateKafkaClusterStatus(newObj.getStatus(), newObj.getKafkaClusterId());
        }
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
            controlPlane.updateKafkaClusterStatus(obj.getStatus(), obj.getKafkaClusterId());
        }
    }
}