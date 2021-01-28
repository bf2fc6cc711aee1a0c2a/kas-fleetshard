package org.bf2.sync.informer;

import java.util.List;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;

/**
 * Provides an interface to lookup the local (informer) state
 */
public interface LocalLookup {

    ManagedKafka getLocalManagedKafka(ManagedKafka remote);

    List<ManagedKafka> getLocalManagedKafkas();

    // getLocalManagedKafkaAgent
}
