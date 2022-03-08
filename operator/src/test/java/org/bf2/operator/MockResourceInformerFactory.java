package org.bf2.operator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.WatchListDeletable;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.test.Mock;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import javax.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Mock
@ApplicationScoped
public class MockResourceInformerFactory extends ResourceInformerFactory {

    @Override
    public <T extends HasMetadata> ResourceInformer<T> create(Class<T> type,
            WatchListDeletable<T, ? extends KubernetesResourceList<T>> watchListDeletable,
            ResourceEventHandler<? super T> eventHandler) {
        ResourceInformer<T> mock = Mockito.mock(ResourceInformer.class);
        Mockito.when(mock.getList()).then(new Answer<List<T>>() {

            @Override
            public List<T> answer(InvocationOnMock invocation) throws Throwable {
                return watchListDeletable.list().getItems();
            }

        });
        Mockito.when(mock.getByKey(Mockito.anyString())).then(new Answer<T>() {

            @Override
            public T answer(InvocationOnMock invocation) throws Throwable {
                String metaNamespaceKey = (String)invocation.getArgument(0);
                String[] parts = metaNamespaceKey.split("/");
                String name;
                String namespace;
                if (parts.length == 2) {
                    name = parts[1];
                    namespace = parts[0];
                } else {
                    name = metaNamespaceKey;
                    namespace = null;
                }
                return watchListDeletable.list()
                        .getItems()
                        .stream()
                        .filter(i -> Objects.equals(name, i.getMetadata().getName())
                                && Objects.equals(namespace, i.getMetadata().getNamespace()))
                        .findFirst().orElse(null);
            }

        });
        Mockito.when(mock.getByNamespace(Mockito.anyString())).then(new Answer<List<T>>() {

            @Override
            public List<T> answer(InvocationOnMock invocation) throws Throwable {
                String namespace = (String)invocation.getArgument(0);
                return watchListDeletable.list()
                        .getItems()
                        .stream()
                        .filter(i -> Objects.equals(namespace, i.getMetadata().getNamespace()))
                        .collect(Collectors.toList());
            }

        });
        return mock;
    }

    @Override
    public boolean allInformersWatching() {
        return true;
    }

}
