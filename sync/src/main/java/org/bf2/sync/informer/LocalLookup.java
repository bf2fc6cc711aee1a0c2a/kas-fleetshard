package org.bf2.sync.informer;

import java.util.List;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;

import io.fabric8.kubernetes.client.informers.cache.Cache;

/**
 * Provides an interface to lookup the local (informer) state
 */
public interface LocalLookup {

    /**
     * @see Cache#metaNamespaceKeyFunc(Object) for the key
     */
    ManagedKafka getLocalManagedKafka(String metaNamespaceKey);

    List<ManagedKafka> getLocalManagedKafkas();

    ManagedKafkaAgent getLocalManagedKafkaAgent();

    boolean isReady();
}
