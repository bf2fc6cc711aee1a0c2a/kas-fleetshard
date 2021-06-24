package org.bf2.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.WatchListDeletable;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;

import javax.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class ResourceInformerFactory {

    private ConcurrentLinkedQueue<ResourceInformer<?>> startedInformers = new ConcurrentLinkedQueue<>();

    public <T extends HasMetadata> ResourceInformer<T> create(Class<T> type,
            WatchListDeletable<T, ? extends KubernetesResourceList<T>> watchListDeletable,
            ResourceEventHandler<? super T> eventHandler) {
        ResourceInformer<T> result = new ResourceInformer<>(type, watchListDeletable, eventHandler);
        startedInformers.add(result);
        return result;
    }

    /**
     * Return true if all informers are watching.  Will be false only after something
     * has abnormally failed with the watch.
     */
    public boolean allInformersWatching() {
        return startedInformers.stream().allMatch(ResourceInformer::isWatching);
    }

}
