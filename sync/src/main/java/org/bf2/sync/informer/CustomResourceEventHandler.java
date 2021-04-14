package org.bf2.sync.informer;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.informers.cache.Indexer;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Simple generic handler.  The consumer should be non-blocking.
 */
final class CustomResourceEventHandler<T extends CustomResource<?,?>> implements ResourceEventHandler<T> {

    static Logger log = Logger.getLogger(CustomResourceEventHandler.class);

    private BiConsumer<T, T> consumer;
    private Indexer<T> indexer;

    public CustomResourceEventHandler(BiConsumer<T, T> consumer, Indexer<T> indexer) {
        this.consumer = consumer;
        this.indexer = indexer;
    }

    public static <T extends CustomResource<?,?>> CustomResourceEventHandler<T> of(BiConsumer<T, T> consumer,
            Indexer<T> indexer) {
        return new CustomResourceEventHandler<T>(consumer, indexer);
    }

    @Override
    public void onAdd(T obj) {
        if (log.isTraceEnabled()) {
            log.tracef("Add event for %s", Cache.metaNamespaceKeyFunc(obj));
        }
        consumer.accept(null, obj);
    }

    @Override
    public void onDelete(T obj, boolean deletedFinalStateUnknown) {
        if (log.isTraceEnabled()) {
            log.tracef("Delete event for %s", Cache.metaNamespaceKeyFunc(obj));
        }
        // this will depend upon the delete strategy chosen
        // currently there is nothing for sync to do on delete
        if (deletedFinalStateUnknown) {
            this.indexer.delete(obj);
        }
    }

    @Override
    public void onUpdate(T oldObj, T newObj) {
        // an update will also be generated for each resyncPeriodInMillis
        // which is a way to ensure the consumer has an up-to-date state
        // even if something is missed - we don't need to consider these
        if (Objects.equals(oldObj.getMetadata().getResourceVersion(), newObj.getMetadata().getResourceVersion())) {
            return;
        }
        if (log.isTraceEnabled()) {
            log.tracef("Update event for %s", Cache.metaNamespaceKeyFunc(newObj));
        }
        consumer.accept(oldObj, newObj);
    }

}
