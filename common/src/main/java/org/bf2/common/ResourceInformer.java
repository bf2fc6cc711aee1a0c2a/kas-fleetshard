package org.bf2.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

import java.util.List;

/**
 * This class for for dealing with fabric8 informer issues
 * github.com/fabric8io/kubernetes-client#2992
 * github.com/fabric8io/kubernetes-client/issues/2994
 */
public class ResourceInformer<T extends HasMetadata> {
    private static long INFORMER_SYNC_TIMEOUT = 2*1000L;

    SharedIndexInformer<T> sharedIndexInformer;
    IndexerAwareResourceEventHandler<T> resourceEventStore;

    private volatile String lastSyncResourceVersion;
    private volatile long lastCheckedTime = -1L;

    public ResourceInformer(SharedIndexInformer<T> indexInformer, IndexerAwareResourceEventHandler<T> eventStore){
        this.sharedIndexInformer = indexInformer;
        this.resourceEventStore = eventStore;

        this.sharedIndexInformer.addEventHandler(this.resourceEventStore);
        this.resourceEventStore.setIndexer(this.sharedIndexInformer.getIndexer());
    }

    public T getByKey(String key) {
        return sharedIndexInformer.getIndexer().getByKey(key);
    }

    public List<T> getList(){
        return this.sharedIndexInformer.getIndexer().list();
    }

    public boolean isReady() {
        String version = sharedIndexInformer.lastSyncResourceVersion();

        if (!hasLength(version)) {
            return false;
        }

        long oldCheckedTime = this.lastCheckedTime;
        String oldResourceVersion = this.lastSyncResourceVersion;

        if (oldResourceVersion == null || !oldResourceVersion.equals(version)) {
            this.lastCheckedTime = System.currentTimeMillis();
            this.lastSyncResourceVersion = version;
            return true;
        }

        return sharedIndexInformer.hasSynced()
                || (System.currentTimeMillis() - oldCheckedTime < INFORMER_SYNC_TIMEOUT);
    }

    static boolean hasLength(String value) {
        return value != null && !value.isEmpty();
    }
}
