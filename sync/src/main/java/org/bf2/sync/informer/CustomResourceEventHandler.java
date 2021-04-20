package org.bf2.sync.informer;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.informers.cache.Indexer;
import org.bf2.common.IndexerAwareResourceEventHandler;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Simple generic handler.  The consumer should be non-blocking.
 */
final class CustomResourceEventHandler<T extends CustomResource<?,?>> implements IndexerAwareResourceEventHandler<T> {

    static Logger log = Logger.getLogger(CustomResourceEventHandler.class);

    private BiConsumer<T, T> consumer;
    private Indexer<T> indexer;

    public CustomResourceEventHandler(BiConsumer<T, T> consumer) {
        this.consumer = consumer;
    }

    public static <T extends CustomResource<?,?>> CustomResourceEventHandler<T> of(BiConsumer<T, T> consumer) {
        return new CustomResourceEventHandler<T>(consumer);
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
            log.tracef("Delete event for %s, with deletedStateUknown %s", Cache.metaNamespaceKeyFunc(obj),
                    deletedFinalStateUnknown);
        }
        // this will depend upon the delete strategy chosen
        // currently there is nothing for sync to do on delete

        // TODO: remove when below issue is resolved and in the quarkus version being used
        // This is workaround for bug in the fabric8 around missed delete event
        // failure to reconcile during resync in DefaultSharedIndexInformer#handleDeltas
        // https://github.com/fabric8io/kubernetes-client/issues/2994
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

    @Override
    public void setIndexer(Indexer<T> indexer) {
        this.indexer = indexer;
    }
}
