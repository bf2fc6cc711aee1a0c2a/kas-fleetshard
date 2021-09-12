package org.bf2.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.WatchListDeletable;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

import javax.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class ResourceInformerFactory {

    private ConcurrentLinkedQueue<SharedIndexInformer<?>> startedInformers = new ConcurrentLinkedQueue<>();

    public <T extends HasMetadata> ResourceInformer<T> create(Class<T> type,
            WatchListDeletable<T, ? extends KubernetesResourceList<T>> watchListDeletable,
            ResourceEventHandler<? super T> eventHandler) {
        SharedIndexInformer<T> informer = watchListDeletable.inform((ResourceEventHandler) eventHandler);
        startedInformers.add(informer);
        return new ResourceInformer<>(informer);
    }

    /**
     * Return true if all informers are watching.  Will be false only after something
     * has abnormally failed with the watch.
     */
    public boolean allInformersWatching() {
        return startedInformers.stream().allMatch(SharedIndexInformer::isWatching);
    }

}
