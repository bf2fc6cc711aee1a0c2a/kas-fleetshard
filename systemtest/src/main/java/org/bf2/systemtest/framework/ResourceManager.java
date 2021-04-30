package org.bf2.systemtest.framework;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.framework.resource.NamespaceResourceType;
import org.bf2.systemtest.framework.resource.ResourceType;
import org.bf2.test.k8s.KubeClient;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Managing resources
 */
public class ResourceManager {
    private static final Logger LOGGER = LogManager.getLogger(ResourceManager.class);

    private static final Map<String, Stack<ThrowableRunner>> storedResources = new LinkedHashMap<>();

    private final KubeClient kubeClient = KubeClient.getInstance();

    private static ResourceManager instance;

    public static synchronized ResourceManager getInstance() {
        if (instance == null) {
            instance = new ResourceManager();
        }
        return instance;
    }

    public void deleteResources(ExtensionContext testContext) throws Exception {
        LOGGER.info("----------------------------------------------");
        LOGGER.info("Going to clear all resources for {}", testContext.getDisplayName());
        LOGGER.info("----------------------------------------------");
        if (!storedResources.containsKey(testContext.getDisplayName())
                || storedResources.get(testContext.getDisplayName()).isEmpty()) {
            LOGGER.info("Nothing to delete");
        }
        while (storedResources.containsKey(testContext.getDisplayName())
                && !storedResources.get(testContext.getDisplayName()).isEmpty()) {
            storedResources.get(testContext.getDisplayName()).pop().run();
        }
        LOGGER.info("----------------------------------------------");
        LOGGER.info("");
        storedResources.remove(testContext.getDisplayName());
    }

    //------------------------------------------------------------------------------------------------
    // Common resource methods
    //------------------------------------------------------------------------------------------------

    private final ResourceType<?>[] resourceTypes = new ResourceType[] {
            new ManagedKafkaResourceType(),
            new NamespaceResourceType(),
    };

    @SafeVarargs
    public final <T extends HasMetadata> void addResource(ExtensionContext testContext, T... resources) {
        for (T resource : resources) {
            synchronized (this) {
                storedResources.computeIfAbsent(testContext.getDisplayName(), k -> new Stack<>());
                storedResources.get(testContext.getDisplayName()).push(() -> deleteResource(resource));
            }
        }
    }

    @SafeVarargs
    public final <T extends HasMetadata> void createResource(ExtensionContext testContext, TimeoutBudget waitDuration,
            T... resources) {
        createResource(testContext, true, waitDuration, resources);
    }

    @SafeVarargs
    public final <T extends HasMetadata> void createResource(ExtensionContext testContext, T... resources) {
        createResource(testContext, true, TimeoutBudget.ofDuration(Duration.ofMinutes(10)), resources);
    }

    @SafeVarargs
    public final <T extends HasMetadata> void createResource(ExtensionContext testContext, boolean waitReady,
            T... resources) {
        createResource(testContext, waitReady, null, resources);
    }

    @SafeVarargs
    private final <T extends HasMetadata> void createResource(ExtensionContext testContext, boolean waitReady,
            TimeoutBudget timeout, T... resources) {
        for (T resource : resources) {
            ResourceType<T> type = findResourceType(resource);
            if (type == null) {
                LOGGER.warn("Can't find resource in list, please create it manually");
                continue;
            }

            // Convenience for tests that create resources in non-existing namespaces. This will create and clean them up.
            synchronized (this) {
                if (resource.getMetadata().getNamespace() != null
                        && !kubeClient.namespaceExists(resource.getMetadata().getNamespace())) {
                    createResource(testContext, waitReady,
                            new NamespaceBuilder().editOrNewMetadata()
                                    .withName(resource.getMetadata().getNamespace())
                                    .endMetadata()
                                    .build());
                }
            }
            LOGGER.info("Create/Update of {} {} in namespace {}",
                    resource.getKind(), resource.getMetadata().getName(),
                    resource.getMetadata().getNamespace() == null ? "(not set)"
                            : resource.getMetadata().getNamespace());

            type.create(resource);

            synchronized (this) {
                storedResources.computeIfAbsent(testContext.getDisplayName(), k -> new Stack<>());
                storedResources.get(testContext.getDisplayName()).push(() -> deleteResource(resource));
            }
        }

        if (waitReady) {
            for (T resource : resources) {
                ResourceType<T> type = findResourceType(resource);
                if (type == null) {
                    LOGGER.warn("Can't find resource in list, please create it manually");
                    continue;
                }

                assertTrue(waitResourceCondition(resource, type::isReady, timeout),
                        String.format("Timed out waiting for %s %s in namespace %s to be ready", resource.getKind(),
                                resource.getMetadata().getName(), resource.getMetadata().getNamespace()));

                T updated = type.get(resource.getMetadata().getNamespace(), resource.getMetadata().getName());
                type.refreshResource(resource, updated);
            }
        }
    }

    @SafeVarargs
    public final <T extends HasMetadata> void deleteResource(T... resources) throws Exception {
        for (T resource : resources) {
            ResourceType<T> type = findResourceType(resource);
            if (type == null) {
                LOGGER.warn("Can't find resource type, please delete it manually");
                continue;
            }
            LOGGER.info("Delete of {} {} in namespace {}",
                    resource.getKind(), resource.getMetadata().getName(),
                    resource.getMetadata().getNamespace() == null ? "(not set)"
                            : resource.getMetadata().getNamespace());
            type.delete(resource);
            assertTrue(waitResourceCondition(resource, Objects::isNull),
                    String.format("Timed out deleting %s %s in namespace %s", resource.getKind(),
                            resource.getMetadata().getName(), resource.getMetadata().getNamespace()));

        }
    }

    public final <T extends HasMetadata> boolean waitResourceCondition(T resource, Predicate<T> condition) {
        return waitResourceCondition(resource, condition, TimeoutBudget.ofDuration(Duration.ofMinutes(5)));
    }

    public final <T extends HasMetadata> boolean waitResourceCondition(T resource, Predicate<T> condition,
            TimeoutBudget timeout) {
        assertNotNull(resource);
        assertNotNull(resource.getMetadata());
        assertNotNull(resource.getMetadata().getName());
        ResourceType<T> type = findResourceType(resource);
        assertNotNull(type);

        while (!timeout.timeoutExpired()) {
            T res = type.get(resource.getMetadata().getNamespace(), resource.getMetadata().getName());
            if (condition.test(res)) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        T res = type.get(resource.getMetadata().getNamespace(), resource.getMetadata().getName());
        boolean pass = condition.test(res);
        if (!pass) {
            LOGGER.info("Resource failed condition check: {}", resourceToString(res));
        }
        return pass;
    }

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public static <T extends HasMetadata> String resourceToString(T resource) {
        if (resource == null) {
            return "null";
        }
        try {
            return mapper.writeValueAsString(resource);
        } catch (JsonProcessingException e) {
            LOGGER.info("Failed converting resource to YAML: {}", e.getMessage());
            return "unknown";
        }
    }

    @SuppressWarnings(value = "unchecked")
    private <T extends HasMetadata> ResourceType<T> findResourceType(T resource) {
        for (ResourceType<?> type : resourceTypes) {
            if (type.getKind().equals(resource.getKind())) {
                return (ResourceType<T>) type;
            }
        }
        return null;
    }

}
