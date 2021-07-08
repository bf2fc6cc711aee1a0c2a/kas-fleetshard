package org.bf2.systemtest.framework.resource;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.test.k8s.KubeClient;

public class NamespaceResourceType implements ResourceType<Namespace> {

    @Override
    public Resource<Namespace> resource(KubeClient client, Namespace resource) {
        return client.client().namespaces().withName(resource.getMetadata().getName());
    }

}
