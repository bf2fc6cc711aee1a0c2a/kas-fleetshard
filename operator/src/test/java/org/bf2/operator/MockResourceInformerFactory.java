package org.bf2.operator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.Gettable;
import io.fabric8.kubernetes.client.dsl.Informable;
import io.fabric8.kubernetes.client.dsl.Listable;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.test.Mock;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import javax.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Mock
@ApplicationScoped
@UnlessBuildProfile("prod")
public class MockResourceInformerFactory extends ResourceInformerFactory {

    @Override
    public <T extends HasMetadata> ResourceInformer<T> create(Class<T> type, Informable<T> informable,
            ResourceEventHandler<? super T> eventHandler) {
        ResourceInformer<T> mock = Mockito.mock(ResourceInformer.class);
        Supplier<List<T>> lister = () -> {
            if (informable instanceof Listable) {
                return ((Listable<KubernetesResourceList<T>>) informable).list().getItems();
            }
            return Collections.singletonList(((Gettable<T>) informable).get());
        };
        Mockito.when(mock.getList()).then(new Answer<List<T>>() {

            @Override
            public List<T> answer(InvocationOnMock invocation) throws Throwable {
                return lister.get();
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
                return lister.get()
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
                return lister.get()
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
