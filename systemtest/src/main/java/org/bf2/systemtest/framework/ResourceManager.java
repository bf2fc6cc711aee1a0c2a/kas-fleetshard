package org.bf2.systemtest.framework;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.framework.resource.NamespaceResourceType;
import org.bf2.systemtest.framework.resource.ResourceType;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Managing resources
 */
public class ResourceManager {
    private static final Logger LOGGER = LogManager.getLogger(ResourceManager.class);

    private static ResourceManager instance = new ResourceManager();

    private static final Map<Class<? extends HasMetadata>, ResourceType<? extends HasMetadata>> KNOWN_TYPES =
            Map.of(Namespace.class, new NamespaceResourceType(), ManagedKafka.class, new ManagedKafkaResourceType());

    private final Map<String, Stack<Runnable>> storedResources = new LinkedHashMap<>();

    private KubeClient client = KubeClient.getInstance();

    public static ResourceManager getInstance() {
        return instance;
    }

    public void deleteResources(ExtensionContext testContext) throws Exception {
        LOGGER.info("----------------------------------------------");
        LOGGER.info("Going to clear all resources for {}", testContext.getDisplayName());
        LOGGER.info("----------------------------------------------");
        if (!storedResources.containsKey(testContext.getDisplayName()) || storedResources.get(testContext.getDisplayName()).isEmpty()) {
            LOGGER.info("Nothing to delete");
        }
        while (storedResources.containsKey(testContext.getDisplayName()) && !storedResources.get(testContext.getDisplayName()).isEmpty()) {
            storedResources.get(testContext.getDisplayName()).pop().run();
        }
        LOGGER.info("----------------------------------------------");
        LOGGER.info("");
        storedResources.remove(testContext.getDisplayName());
    }

    @SafeVarargs
    public final <T extends HasMetadata> void addResource(ExtensionContext testContext, T... resources) {
        for (T resource : resources) {
            synchronized (this) {
                storedResources.computeIfAbsent(testContext.getDisplayName(), k -> new Stack<>());
                storedResources.get(testContext.getDisplayName()).push(() -> deleteResource(resource));
            }
        }
    }

    public final <T extends HasMetadata> T createResource(ExtensionContext testContext, long timeout, T resource) {
        return createResourceAndWait(testContext, timeout, resource);
    }

    public final <T extends HasMetadata> T createResource(ExtensionContext testContext, T resource) {
        return createResource(testContext, TimeUnit.MINUTES.toMillis(10), resource);
    }

    private final <T extends HasMetadata> T createResourceAndWait(ExtensionContext testContext, Long timeout, T resource) {
        // Convenience for tests that create resources in non-existing namespaces. This will create and clean them up.
        synchronized (this) {
            if (resource.getMetadata().getNamespace() != null && !client.namespaceExists(resource.getMetadata().getNamespace())) {
                createResourceAndWait(testContext, null, new NamespaceBuilder().editOrNewMetadata().withName(resource.getMetadata().getNamespace()).endMetadata().build());
            }
        }
        LOGGER.info("Create/Update of {} {} in namespace {}",
                resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace() == null ? "(not set)" : resource.getMetadata().getNamespace());

        @SuppressWarnings("unchecked")
        T result = findResource(resource).createOrReplace(resource);

        synchronized (this) {
            storedResources.computeIfAbsent(testContext.getDisplayName(), k -> new Stack<>());
            storedResources.get(testContext.getDisplayName()).push(() -> deleteResource(resource));
        }

        if (timeout != null) {
            result = waitUntilReady(resource, timeout);
        }
        return result;
    }

    public <T extends HasMetadata> T waitUntilReady(T resource, long timeout) {
        return waitResourceCondition(resource, findResourceType(resource).readiness(client), timeout);
    }

    private <T extends HasMetadata> Resource<T> findResource(T resource) {
        return findResourceType(resource).resource(client, resource);
    }

    @SafeVarargs
    public final <T extends HasMetadata> void deleteResource(T... resources) {
        for (T resource : resources) {
            LOGGER.info("Delete of {} {} in namespace {}",
                    resource.getKind(), resource.getMetadata().getName(), resource.getMetadata().getNamespace() == null ? "(not set)" : resource.getMetadata().getNamespace());
            findResource(resource).delete();
            waitResourceCondition(resource, Objects::isNull);
        }
    }

    public final <T extends HasMetadata> T waitResourceCondition(T resource, Predicate<T> condition) {
        return waitResourceCondition(resource, condition, TimeUnit.MINUTES.toMillis(5));
    }

    public static <T extends HasMetadata> String resourceToString(T resource) {
        return Serialization.asYaml(resource);
    }

    public final <T extends HasMetadata> T waitResourceCondition(T resource, Predicate<T> condition, long timeout) {
        // we use polling logic interval because the readiness tests can look at other resources
        AtomicReference<T> result = new AtomicReference<>();
        Resource<T> r = findResource(resource);
        TestUtils.waitFor(resource.getMetadata().getName(), 1000, timeout, () -> {
            T value = r.get();
            if (condition.test(value)) {
                result.set(value);
                return true;
            }
            return false;
        });
        return result.get();
    }

    @SuppressWarnings(value = "unchecked")
    private <T extends HasMetadata> ResourceType<T> findResourceType(T resource) {
        ResourceType<T> type = (ResourceType<T>) KNOWN_TYPES.get(resource.getClass());
        if (type == null) {
            throw new IllegalArgumentException(String.format("Unknown resource type %s", resource.getClass().getName()));
        }
        return type;
    }

}
