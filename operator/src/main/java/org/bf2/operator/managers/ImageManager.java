package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.runtime.Startup;
import org.bf2.common.ResourceInformerFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Startup
@ApplicationScoped
public class ImageManager {

    public static final String CANARY = "canary";
    public static final String CANARY_INIT = "canary_init";
    public static final String ADMIN_API = "admin_api";

    private Map<String, Map<String, String>> otherImages = new ConcurrentHashMap<>();

    @ConfigProperty(name = "image.admin-api")
    String adminApiImage;

    @ConfigProperty(name = "image.canary")
    String canaryImage;

    @ConfigProperty(name = "image.canary-init")
    String canaryInitImage;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    @Inject
    InformerManager informerManager;

    @PostConstruct
    protected void onStart() {
        this.resourceInformerFactory.create(ConfigMap.class,
                this.kubernetesClient.configMaps()
                        .inAnyNamespace()
                        // TODO: we can use whatever label we want here correct?
                        .withLabels(Map.of("app.kubernetes.io/part-of", "managed-kafka")),
                new ResourceEventHandler<ConfigMap>() {
                    @Override
                    public void onAdd(ConfigMap obj) {
                        otherImages.put(obj.getMetadata().getName(), obj.getData());
                        informerManager.resyncManagedKafka();
                    }

                    @Override
                    public void onDelete(ConfigMap obj, boolean deletedFinalStateUnknown) {
                        otherImages.remove(obj.getMetadata().getName());
                        informerManager.resyncManagedKafka();
                    }

                    @Override
                    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) {
                        onAdd(newObj);
                    }
                });

    }

    private Map<String, String> getImagesForVersion(String strimzi) {
        return otherImages.getOrDefault(strimzi == null ? "" : strimzi, Collections.emptyMap());
    }

    public String getCanaryImage(String strimzi) {
        return getImagesForVersion(strimzi).getOrDefault(CANARY, canaryImage);
    }

    public String getCanaryInitImage(String strimzi) {
        return getImagesForVersion(strimzi).getOrDefault(CANARY_INIT, canaryInitImage);
    }

    public String getAdminApiImage(String strimzi) {
        return getImagesForVersion(strimzi).getOrDefault(ADMIN_API, adminApiImage);
    }

}
