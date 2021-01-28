package org.bf2.sync.informer;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;

/**
 * Provides an interface to lookup the local (informer) state
 */
public interface LocalLookup {

    ManagedKafka getLocalManagedKafka(ManagedKafka remote);

    // getLocalManagedKafkaAgent
}
