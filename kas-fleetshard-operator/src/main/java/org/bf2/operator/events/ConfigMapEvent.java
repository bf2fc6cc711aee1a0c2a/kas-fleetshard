package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.javaoperatorsdk.operator.processing.event.AbstractEvent;

public class ConfigMapEvent extends AbstractEvent {

    private ConfigMap configMap;

    public ConfigMapEvent(ConfigMap configMap, ConfigMapEventSource configMapEventSource) {
        super(configMap.getMetadata().getOwnerReferences().get(0).getUid(), configMapEventSource);
        this.configMap = configMap;
    }

    public ConfigMap getConfigMap() {
        return configMap;
    }
}
