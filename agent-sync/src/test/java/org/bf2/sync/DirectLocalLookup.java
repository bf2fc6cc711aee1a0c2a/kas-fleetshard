package org.bf2.sync;

import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.sync.client.ManagedKafkaResourceClient;
import org.bf2.sync.informer.LocalLookup;

import io.quarkus.test.Mock;

/**
 * To bypass the informer async update, we can lookup directly
 * against the (Mock) server
 */
@Mock
@ApplicationScoped
public class DirectLocalLookup implements LocalLookup {

    @Inject
    ManagedKafkaResourceClient client;

    @Override
    public ManagedKafka getLocalManagedKafka(String metaNamespaceKey) {
        String[] parts = metaNamespaceKey.split("/");
        return client.getByName(parts[0], parts[1]);
    }

    @Override
    public List<ManagedKafka> getLocalManagedKafkas() {
        return client.list();
    }

}
