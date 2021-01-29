package org.bf2.sync.informer;

import java.util.function.Consumer;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;

/**
 * Simple generic handler.  The consumer should be async.
 */
final class CustomResourceEventHandler<T extends CustomResource<?,?>> implements ResourceEventHandler<T> {

    private Consumer<T> consumer;

    public CustomResourceEventHandler(Consumer<T> consumer) {
        this.consumer = consumer;
    }

    @Override
    public void onAdd(T obj) {
        if (obj.getStatus() != null) {
            consumer.accept(obj);
        }
    }

    @Override
    public void onDelete(T obj, boolean deletedFinalStateUnknown) {
        // TODO: this will depend upon the delete strategy chosen
        // currently there is nothing for sync to do on delete
    }

    @Override
    public void onUpdate(T oldObj, T newObj) {
        // an update will also be generated for each resyncPeriodInMillis
        // which is a way to ensure the consumer has an up-to-date state
        // even if something is missed
        if (newObj.getStatus() != null) {
            consumer.accept(newObj);
        }
    }

}