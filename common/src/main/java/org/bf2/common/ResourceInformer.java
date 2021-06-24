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
 * <br>All calls to the ResourceEventHandler happen from either the calling or Watcher thread - it's expected to be fast/non-blocking.
 * <br>start forces the initial sync inline with the calling thread - which may be relied upon in code that use the inform
 *     upstream fabric8-client should be able to accomodate this behavior in 5.5 with the Informable interface.
 */
public class ResourceInformer<T extends HasMetadata> {
    private static final int WATCH_WAIT = 5000; // time between watch attempts

    private static Logger log = Logger.getLogger(ResourceInformer.class);

    private String typeName;
    private WatchListDeletable<T, ? extends KubernetesResourceList<T>> watchListDeletable;
    private ResourceEventHandler<? super T> eventHandler;

    private ConcurrentHashMap<String, T> cache = new ConcurrentHashMap<>();

    private volatile String lastResourceVersion;
    private volatile boolean watching;

    private Watcher<T> watcher = new Watcher<T>() {
        @Override
        public void eventReceived(Action action, T resource) {
            // a log here would duplicate what we have in the resource handlers
            // so we just log the special case of duplicate delete below
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
                delete(Cache.metaNamespaceKeyFunc(resource), false);
                break;
            }
        }

        /**
         * Has the responsibility of re-establishing the watch or reporting this as not ready
         */
        @Override
        public void onClose(WatcherException cause) {
            watching = false;
            log.infof("%s Informer watch needs restarted", typeName, cause);
            boolean gone = cause.isHttpGone();
            while (true) {
                try {
                    if (gone) {
                        list();
                        gone = false;
                    } else {
                        // note this (and the other watch restart not related to a relist) should not necessary
                        // in 5.3.1 or later with reconnecting support see https://github.com/fabric8io/kubernetes-client/pull/3018
                        Thread.sleep(WATCH_WAIT);
                    }
                    watch();
                    return;
                } catch (InterruptedException e) {
                    log.warn("Terminating watch due to interrupt");
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.errorf("Error restarting %s informer watch", typeName, e);
                }
            }
        }

        @Override
        public void onClose() {
            log.errorf("%s Informer watch closing without error", typeName);
            watching = false;
            throw new IllegalStateException();
        }

    };

    ResourceInformer(Class<T> type, WatchListDeletable<T, ? extends KubernetesResourceList<T>> watchListDeletable,
            ResourceEventHandler<? super T> eventHandler) {
        this.typeName = type.getSimpleName();
        this.watchListDeletable = watchListDeletable;
        this.eventHandler = eventHandler;
        list();
        watch();
    }

    private void watch() {
        log.debugf("Starting watch at version %s", lastResourceVersion);
        watchListDeletable.watch(new ListOptionsBuilder()
                .withWatch(Boolean.TRUE)
                .withResourceVersion(lastResourceVersion)
                .build(), watcher);
        watching = true;
    }

    private void list() {
        KubernetesResourceList<T> list = watchListDeletable.list();
        log.debugf("Got a fresh list %s version %s", list.getClass().getSimpleName(), list.getMetadata().getResourceVersion());
        lastResourceVersion = list.getMetadata().getResourceVersion();
        Map<String, T> newItems = list.getItems()
                .stream()
                .collect(Collectors.toMap(Cache::metaNamespaceKeyFunc, Function.identity()));
        cache.forEach((k, v) -> {
            T newItem = newItems.get(k);
            if (newItem == null) {
                // unknown because this is coming via a missing list entry
                delete(k, true);
            }
        });
        newItems.forEach((k, v) -> {
            update(k, v);
        });
    }

    private void delete(String k, boolean unknownLastState) {
        T old = cache.remove(k);
        if (old != null) {
            try {
                eventHandler.onDelete(old, unknownLastState);
            } catch (Exception e) {
                log.warn("Unhandled exception from event handler", e);
            }
        } else {
            log.debugf("Duplicate delete received for %s", k);
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

    public boolean isWatching() {
        return watching;
    }

}
