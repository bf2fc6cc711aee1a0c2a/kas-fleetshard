package org.bf2.systemtest.framework.resource;

import org.bf2.test.k8s.KubeClient;

import io.fabric8.kubernetes.api.model.Namespace;

public class NamespaceResourceType implements ResourceType<Namespace> {
    @Override
    public String getKind() {
        return "Namespace";
    }

    @Override
    public Namespace get(String namespace, String name) {
        return KubeClient.getInstance().client().namespaces().withName(name).get();
    }

    @Override
    public void create(Namespace resource) {
        KubeClient.getInstance().client().namespaces().createOrReplace(resource);
    }

    @Override
    public void delete(Namespace resource) throws Exception {
        KubeClient.getInstance().client().namespaces().withName(resource.getMetadata().getName()).delete();
    }

    @Override
    public boolean isReady(Namespace resource) {
        return resource != null;
    }

    @Override
    public void refreshResource(Namespace existing, Namespace newResource) {
        existing.setMetadata(newResource.getMetadata());
        existing.setSpec(newResource.getSpec());
        existing.setStatus(newResource.getStatus());
    }
}
