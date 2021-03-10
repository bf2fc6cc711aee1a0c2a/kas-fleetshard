package org.bf2.sync;

import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.sync.informer.LocalLookup;

import io.fabric8.kubernetes.client.KubernetesClient;
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

    @Inject
    KubernetesClient kubeClient;

    @Override
    public ManagedKafka getLocalManagedKafka(String metaNamespaceKey) {
        String[] parts = metaNamespaceKey.split("/");
        return client.getByName(parts[0], parts[1]);
    }

    @Override
    public List<ManagedKafka> getLocalManagedKafkas() {
        return client.list();
    }

    @Override
    public ManagedKafkaAgent getLocalManagedKafkaAgent() {
        List<ManagedKafkaAgent> items = kubeClient.customResources(ManagedKafkaAgent.class).list().getItems();
        if (items.isEmpty()) {
            return null;
        }
        return items.get(0);
    }

    @Override
    public boolean isReady() {
        return true;
    }

}
