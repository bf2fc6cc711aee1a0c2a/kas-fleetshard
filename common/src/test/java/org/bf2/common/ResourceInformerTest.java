package org.bf2.common;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.WatchListDeletable;
import io.fabric8.kubernetes.client.dsl.base.OperationSupport;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.HttpURLConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ResourceInformerTest {

    @Test
    void testEvents() {
        ResourceEventHandler<Pod> podEventHandler = Mockito.mock(ResourceEventHandler.class);
        WatchListDeletable<Pod, KubernetesResourceList<Pod>> podWatchList = Mockito.mock(WatchListDeletable.class);

        Pod pod = new PodBuilder().withNewMetadata().withName("name").endMetadata().build();
        PodList podList = new PodListBuilder().withNewMetadata()
                .withResourceVersion("1")
                .endMetadata()
                .withItems(pod)
                .build();
        PodList emptyList = new PodListBuilder().withNewMetadata()
                .withResourceVersion("10")
                .endMetadata()
                .build();
        Mockito.when(podWatchList.list()).thenReturn(podList, emptyList, podList);

        ResourceInformer<Pod> podInformer = ResourceInformer.start(Pod.class, podWatchList, podEventHandler);

        ArgumentCaptor<Watcher<Pod>> watcherCaptor = ArgumentCaptor.forClass(Watcher.class);

        // verify that we saw an add
        Mockito.verify(podEventHandler).onAdd(pod);
        // and the item is in cache
        assertNotNull(podInformer.getByKey(Cache.metaNamespaceKeyFunc(pod)));
        // and other stuff is not
        assertNull(podInformer.getByKey("x"));

        // verify that the watch was established
        Mockito.verify(podWatchList).watch(Mockito.any(ListOptions.class), watcherCaptor.capture());

        Watcher<Pod> watcher = watcherCaptor.getValue();

        // update event
        Pod podmod =
                new PodBuilder().withNewMetadata().withName("name").withClusterName("cluster").endMetadata().build();
        watcher.eventReceived(Action.MODIFIED,
                podmod);

        // check the update and the cache
        Mockito.verify(podEventHandler).onUpdate(pod, podmod);
        assertEquals("cluster",
                podInformer.getByKey(Cache.metaNamespaceKeyFunc(podmod)).getMetadata().getClusterName());

        // gone will relist against empty
        watcher.onClose(new WatcherException(null,
                new KubernetesClientException(OperationSupport.createStatus(HttpURLConnection.HTTP_GONE, null))));

        // delete with final state unknown
        Mockito.verify(podEventHandler).onDelete(podmod, true);
        assertNull(podInformer.getByKey(Cache.metaNamespaceKeyFunc(pod)));

        // gone will relist against the single pod
        watcher.onClose(new WatcherException(null,
                new KubernetesClientException(OperationSupport.createStatus(HttpURLConnection.HTTP_GONE, null))));

        // see that add was called again
        Mockito.verify(podEventHandler, Mockito.times(2)).onAdd(pod);
        assertNotNull(podInformer.getByKey(Cache.metaNamespaceKeyFunc(pod)));

        // delete
        watcher.eventReceived(Action.DELETED, pod);

        // delete with known final state
        Mockito.verify(podEventHandler).onDelete(pod, false);
        assertNull(podInformer.getByKey(Cache.metaNamespaceKeyFunc(pod)));
    }

}
