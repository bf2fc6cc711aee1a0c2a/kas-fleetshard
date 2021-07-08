package org.bf2.systemtest.framework.resource;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.test.k8s.KubeClient;

import java.util.Objects;
import java.util.function.Predicate;

public interface ResourceType<T extends HasMetadata> {
    /**
     * Check if this resource is marked as ready or not.
     */
    default Predicate<T> readiness(KubeClient client) {
        return Objects::nonNull;
    }

    Resource<T> resource(KubeClient client, T resource);

}
