package org.bf2.common;

import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.cache.Indexer;

public interface IndexerAwareResourceEventHandler<T> extends ResourceEventHandler<T> {
    void setIndexer(Indexer<T> indexer);
}
