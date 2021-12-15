package org.bf2.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.cache.Cache;

import java.util.List;

public class ResourceInformer<T extends HasMetadata> {

    private SharedIndexInformer<T> informer;

    public ResourceInformer(SharedIndexInformer<T> informer) {
       this.informer = informer;
    }

    public T getByKey(String metaNamespaceKey) {
        return informer.getStore().getByKey(metaNamespaceKey);
    }

    public List<T> getList() {
        return informer.getStore().list();
    }

    public List<T> getByNamespace(String namesapce) {
        return informer.getIndexer().byIndex(Cache.NAMESPACE_INDEX, namesapce);
    }

    public boolean isWatching() {
        return informer.isWatching();
    }

    public void addResourceEventHandler(ResourceEventHandler<HasMetadata> handler) {
        this.informer.addEventHandler((ResourceEventHandler<T>) handler);
    }

}
