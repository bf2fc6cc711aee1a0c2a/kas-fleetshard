package org.bf2.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.Listable;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * This class for for dealing with fabric8 informer issues
 * github.com/fabric8io/kubernetes-client#2992
 * github.com/fabric8io/kubernetes-client/issues/2994
 */
public class ResourceInformer<T extends HasMetadata> {
    private static Logger log = Logger.getLogger(ResourceInformer.class);

    private static long DEFAULT_INFORMER_SYNC_TIMEOUT = Duration.ofMinutes(2).toMillis();
    private static Optional<Long> CONFIGURED_TIMEOUT =
            ConfigProvider.getConfig().getOptionalValue("informer.watch.health.interval", Long.class);

    SharedIndexInformer<T> sharedIndexInformer;
    IndexerAwareResourceEventHandler<T> resourceEventStore;
    Supplier<Listable<? extends KubernetesResourceList<?>>> lister;
    Class<T> type;

    private volatile String lastSyncResourceVersion;
    private volatile long lastCheckedTime = -1L;
    private volatile Long doubtfulTime;

    public ResourceInformer(SharedIndexInformer<T> indexInformer, IndexerAwareResourceEventHandler<T> eventStore,
            Supplier<Listable<? extends KubernetesResourceList<?>>> lister, Class<T> type) {
        this.sharedIndexInformer = indexInformer;
        this.resourceEventStore = eventStore;
        this.lister = lister;
        this.type = type;

        this.sharedIndexInformer.addEventHandler(this.resourceEventStore);
        this.resourceEventStore.setIndexer(this.sharedIndexInformer.getIndexer());
    }

    public T getByKey(String key) {
        return sharedIndexInformer.getIndexer().getByKey(key);
    }

    public List<T> getList() {
        return this.sharedIndexInformer.getIndexer().list();
    }

    public boolean isReady() {
        String version = sharedIndexInformer.lastSyncResourceVersion();

        if (!hasLength(version)) {
            return false;
        }

        long oldCheckedTime = this.lastCheckedTime;
        String oldResourceVersion = this.lastSyncResourceVersion;

        long currentTime = System.currentTimeMillis();
        if (oldResourceVersion == null || !oldResourceVersion.equals(version)) {
            this.lastCheckedTime = currentTime;
            this.lastSyncResourceVersion = version;
            doubtfulTime = null;
            return true;
        }

        if (currentTime - oldCheckedTime < CONFIGURED_TIMEOUT.orElse(DEFAULT_INFORMER_SYNC_TIMEOUT)) {
            return true;
        }

        String actualLast = lister.get().list().getMetadata().getResourceVersion();
        if (oldResourceVersion.equals(actualLast)) {
            this.lastCheckedTime = currentTime;
            doubtfulTime = null;
            return true;
        }
        // we can either handle this in the yaml config (with two failed checks), or here
        // with a flag.  opting for the code to have more explicit messages
        if (doubtfulTime != null
                && currentTime - doubtfulTime > CONFIGURED_TIMEOUT.orElse(DEFAULT_INFORMER_SYNC_TIMEOUT)) {
            log.warnf("Informer state is wrong for %s, it is stuck at %s but should be at %s", type.getSimpleName(),
                    oldResourceVersion, actualLast);
            return false;
        }
        if (doubtfulTime == null) {
            doubtfulTime = currentTime;
            log.warnf("Informer state is doubtful for %s, it seems stuck at %s", type.getSimpleName(),
                    oldResourceVersion);
        }
        return true;
    }

    static boolean hasLength(String value) {
        return value != null && !value.isEmpty();
    }
}
