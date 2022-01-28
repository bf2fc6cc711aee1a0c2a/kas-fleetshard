package org.bf2.operator.managers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.runtime.Startup;
import org.bf2.common.ResourceInformerFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Startup
@ApplicationScoped
public class OperandOverrideManager {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OperandOverride {
        public String image;

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Canary extends OperandOverride {
        public OperandOverride init = new OperandOverride();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OperandOverrides {
        public Canary canary = new Canary();
        @JsonProperty(value = "admin-server")
        public OperandOverride adminServer = new OperandOverride();
    }

    static final OperandOverrides EMPTY = new OperandOverrides();

    public static final String OPERANDS_YAML = "fleetshard_operands.yaml";

    private Map<String, OperandOverrides> overrides = new ConcurrentHashMap<>();

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

    @Inject
    Logger log;

    @PostConstruct
    protected void onStart() {
        this.resourceInformerFactory.create(ConfigMap.class,
                this.kubernetesClient.configMaps().inAnyNamespace().withLabel("app", "strimzi"),
                new ResourceEventHandler<ConfigMap>() {
                    @Override
                    public void onAdd(ConfigMap obj) {
                        updateOverrides(obj);
                    }

                    @Override
                    public void onDelete(ConfigMap obj, boolean deletedFinalStateUnknown) {
                        removeOverrides(obj);
                    }

                    @Override
                    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) {
                        updateOverrides(newObj);
                    }
                });
    }

    private OperandOverrides getOverrides(String strimzi) {
        return overrides.getOrDefault(strimzi == null ? "" : strimzi, EMPTY);
    }

    public String getCanaryImage(String strimzi) {
        return Optional.ofNullable(getOverrides(strimzi).canary.image).orElse(canaryImage);
    }

    public String getCanaryInitImage(String strimzi) {
        return Optional.ofNullable(getOverrides(strimzi).canary.init.image).orElse(canaryInitImage);
    }

    public String getAdminServerImage(String strimzi) {
        return Optional.ofNullable(getOverrides(strimzi).adminServer.image).orElse(adminApiImage);
    }

    void updateOverrides(ConfigMap obj) {
        String name = obj.getMetadata().getName();
        if (name.startsWith(StrimziManager.STRIMZI_CLUSTER_OPERATOR)) {
            String data = obj.getData().get(OPERANDS_YAML);
            log.infof("Updating overrides for {} to {}", name, data);
            boolean resync = false;
            if (data == null) {
                overrides.remove(name);
                resync = true;
            } else {
                OperandOverrides operands = Serialization.unmarshal(data, OperandOverrides.class);
                OperandOverrides old = overrides.put(name, operands);
                resync = old == null || !Serialization.asYaml(old).equals(Serialization.asYaml(operands));
            }
            if (resync) {
                informerManager.resyncManagedKafka();
            }
        }
    }

    void removeOverrides(ConfigMap obj) {
        String name = obj.getMetadata().getName();
        if (name.startsWith(StrimziManager.STRIMZI_CLUSTER_OPERATOR)) {
            log.infof("removing overrides for {}", name);
            overrides.remove(name);
            informerManager.resyncManagedKafka();
        }
    }

    void resetOverrides() {
        this.overrides.clear();
    }

}
