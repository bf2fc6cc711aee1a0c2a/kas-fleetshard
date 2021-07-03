package org.bf2.common;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.function.UnaryOperator;

public abstract class AbstractCustomResourceClient<T extends CustomResource<?, ?>, L extends CustomResourceList<T>> {

    @Inject
    Logger log;

    @Inject
    protected KubernetesClient kubernetesClient;

    protected MixedOperation<T, L, Resource<T>> resourceClient;

    protected abstract Class<T> getCustomResourceClass();

    protected abstract Class<L> getCustomResourceListClass();

    protected MixedOperation<T, L, Resource<T>> getResourceClient() {
        return kubernetesClient.customResources(getCustomResourceClass(), getCustomResourceListClass());
    }

    @PostConstruct
    void onStart() {
        resourceClient = getResourceClient();
    }

    public void delete(String namespace, String name) {
        resourceClient
                .inNamespace(namespace)
                .withName(name)
                .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                .delete();
    }

    public T getByName(String namespace, String name) {
        return resourceClient
                .inNamespace(namespace)
                .withName(name).get();
    }

    public T create(T resource) {
        return resourceClient.inNamespace(resource.getMetadata().getNamespace()).create(resource);
    }

    public T createOrReplace(T resource) {
        return resourceClient.inNamespace(resource.getMetadata().getNamespace()).createOrReplace(resource);
    }

    public T edit(String namespace, String name, UnaryOperator<T> function) {
        return resourceClient.inNamespace(namespace).withName(name).edit(function);
    }

    public T patch(T resource) {
        return resourceClient.inNamespace(resource.getMetadata().getNamespace()).withName(resource.getMetadata().getName()).patch(resource);
    }

    /**
     * List the resource across all namespaces
     */
    public List<T> list() {
        return resourceClient.inAnyNamespace().list().getItems();
    }

    public boolean updateStatus(T resource) {
        try {
            resourceClient.inNamespace(resource.getMetadata().getNamespace()).withName(resource.getMetadata().getName()).updateStatus(resource);
            return true;
        } catch (KubernetesClientException e) {
            if (e.getCode() == HttpURLConnection.HTTP_CONFLICT) {
                log.infof("Conflict on %s status update", getCustomResourceClass().getSimpleName());
                return false;
            }
            throw e;
        }
    }

    /**
     * Get the default namespace for the client
     */
    public String getNamespace() {
        return kubernetesClient.getNamespace();
    }

    public T createOrUpdate(T resource) {
        return OperandUtils.createOrUpdate(resourceClient, resource);
    }

}
