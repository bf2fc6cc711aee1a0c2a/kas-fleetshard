package org.bf2.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.WatchListDeletable;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This is a slimmed down implementation that eliminates many concerns and addresses issues, such as:
 * <br>https://github.com/fabric8io/kubernetes-client#2992
 * <br>https://github.com/fabric8io/kubernetes-client/issues/2994
 * <br>https://github.com/fabric8io/kubernetes-client/issues/2991
 *
 * <br>All updates to the cache happen from the Watcher thread.
 * <br>All calls to the ResourceEventHandler happen inline - it's expected to be fast/non-blocking.
 * <br>start forces the initial sync inline with the calling thread.
 */
public class ResourceInformer<T extends HasMetadata> {
    private static Logger log = Logger.getLogger(ResourceInformer.class);

    private WatchListDeletable<T, ? extends KubernetesResourceList<T>> watchListDeletable;
    private ResourceEventHandler<T> eventHandler;

    private ConcurrentHashMap<String, T> cache = new ConcurrentHashMap<>();

    private volatile String lastResourceVersion;
    private volatile boolean ready;

    private Watcher<T> watcher = new Watcher<T>() {
        @Override
        public void eventReceived(Action action, T resource) {
            switch (action) {
            case ERROR:
                throw new KubernetesClientException("Error received");
            case ADDED:
            case MODIFIED:
                lastResourceVersion = resource.getMetadata().getResourceVersion();
                update(Cache.metaNamespaceKeyFunc(resource), resource);
                break;
            case DELETED:
                lastResourceVersion = resource.getMetadata().getResourceVersion();
                T old = cache.remove(Cache.metaNamespaceKeyFunc(resource));
                if (old != null) {
                    delete(old, false);
                }
                break;
            }
        }

        /**
         * Has the responsibility of re-establishing the watch or reporting this as not ready
         */
        @Override
        public void onClose(WatcherException cause) {
            boolean restarted = false;
            try {
                log.info("Informer watch needs restarted", cause);
                if (cause.isHttpGone()) {
                    try {
                        list();
                    } catch (Exception e) {
                        log.warn("Error re-listing", e);
                    }
                } else {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        log.warn("Terminating watch due to interupt");
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                try {
                    watch();
                    restarted = true;
                } catch (Exception e) {
                    log.error("Error restarting watch", e);
                }
            } finally {
                if (!restarted) {
                    log.warn("Terminating watch due to interupt");
                    ready = false;
                }
            }
        }

        @Override
        public void onClose() {
            log.info("Explict close of the watch called, it is no longer ready");
            ready = false;
        }

    };

    public static <T extends HasMetadata> ResourceInformer<T> start(
            WatchListDeletable<T, ? extends KubernetesResourceList<T>> watchListDeletable,
            ResourceEventHandler<T> eventHandler) {
        ResourceInformer<T> result = new ResourceInformer<>(watchListDeletable, eventHandler);
        result.list();
        result.watch();
        return result;
    }

    private ResourceInformer(WatchListDeletable<T, ? extends KubernetesResourceList<T>> watchListDeletable,
            ResourceEventHandler<T> eventHandler) {
        this.watchListDeletable = watchListDeletable;
        this.eventHandler = eventHandler;
    }

    private void watch() {
        watchListDeletable.watch(new ListOptionsBuilder()
                .withWatch(Boolean.TRUE)
                .withResourceVersion(lastResourceVersion)
                .withTimeoutSeconds(null)
                .build(), watcher);
        ready = true;
    }

    private void list() {
        KubernetesResourceList<T> list = watchListDeletable.list();
        lastResourceVersion = list.getMetadata().getResourceVersion();
        Map<String, T> newItems = list.getItems()
                .stream()
                .collect(Collectors.toMap(Cache::metaNamespaceKeyFunc, Function.identity()));
        cache.forEach((k, v) -> {
            T newItem = newItems.get(k);
            if (newItem == null) {
                // unknown because this is coming via a missing list entry
                delete(v, true);
            }
        });
        newItems.forEach((k, v) -> {
            update(k, v);
        });
    }

    private void delete(T old, boolean unknownLastState) {
        try {
            eventHandler.onDelete(old, unknownLastState);
        } catch (Exception e) {
            log.warn("Unhandled exception from event handler", e);
        }
    }

    private void update(String k, T v) {
        T old = cache.put(k, v);
        try {
            if (old == null) {
                eventHandler.onAdd(v);
            } else {
                eventHandler.onUpdate(old, v);
            }
        } catch (Exception e) {
            log.warn("Unhandled exception from event handler", e);
        }
    }

    public T getByKey(String metaNamespaceKey) {
        return cache.get(metaNamespaceKey);
    }

    public List<T> getList() {
        return new ArrayList<>(cache.values());
    }

    public String getLastResourceVersion() {
        return lastResourceVersion;
    }

    public boolean isReady() {
        return ready;
    }

}
