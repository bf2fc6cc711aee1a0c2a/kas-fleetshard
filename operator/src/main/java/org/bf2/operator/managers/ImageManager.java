package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.runtime.Startup;
import org.bf2.common.ResourceInformerFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Startup
@ApplicationScoped
public class ImageManager {

    public static final String CANARY = "canary";
    public static final String CANARY_INIT = "canary_init";
    public static final String ADMIN_API = "admin_api";

    public static final String IMAGES_YAML = "images.yaml";

    private static Properties EMPTY = new Properties();

    private Map<String, Properties> otherImages = new ConcurrentHashMap<>();

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
                        .withLabel("app", "strimzi"),
                new ResourceEventHandler<ConfigMap>() {
                    @Override
                    public void onAdd(ConfigMap obj) {
                        String name = obj.getMetadata().getName();
                        if (name.startsWith(StrimziManager.STRIMZI_CLUSTER_OPERATOR)) {
                            String data = obj.getData().get(IMAGES_YAML);
                            boolean resync = false;
                            if (data == null) {
                                otherImages.remove(name);
                                resync = true;
                            } else {
                                Properties p = Serialization.unmarshal(data, Properties.class);
                                Properties old = otherImages.put(name, p);
                                resync = !Objects.equals(p, old);
                            }
                            if (resync) {
                                informerManager.resyncManagedKafka();
                            }
                        }
                    }

                    @Override
                    public void onDelete(ConfigMap obj, boolean deletedFinalStateUnknown) {
                        String name = obj.getMetadata().getName();
                        if (name.startsWith(StrimziManager.STRIMZI_CLUSTER_OPERATOR)) {
                            otherImages.remove(name);
                            informerManager.resyncManagedKafka();
                        }
                    }

                    @Override
                    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) {
                        onAdd(newObj);
                    }
                });

    }

    private Properties getImagesForVersion(String strimzi) {
        return otherImages.getOrDefault(strimzi == null ? "" : strimzi, EMPTY);
    }

    public String getCanaryImage(String strimzi) {
        return getImagesForVersion(strimzi).getProperty(CANARY, canaryImage);
    }

    public String getCanaryInitImage(String strimzi) {
        return getImagesForVersion(strimzi).getProperty(CANARY_INIT, canaryInitImage);
    }

    public String getAdminApiImage(String strimzi) {
        return getImagesForVersion(strimzi).getProperty(ADMIN_API, adminApiImage);
    }

}
