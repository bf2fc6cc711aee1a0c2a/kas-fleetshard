package org.bf2.operator.events;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ConfigMapEventSource extends AbstractEventSource implements ResourceEventHandler<ConfigMap> {

    private static final Logger log = LoggerFactory.getLogger(ConfigMapEventSource.class);

    @Override
    public void onAdd(ConfigMap configMap) {
        log.info("Add event received for ConfigMap {}/{}", configMap.getMetadata().getNamespace(), configMap.getMetadata().getName());
        handleEvent(configMap);
    }

    @Override
    public void onUpdate(ConfigMap oldConfigMap, ConfigMap newConfigMap) {
        log.info("Update event received for ConfigMap {}/{}", oldConfigMap.getMetadata().getNamespace(), oldConfigMap.getMetadata().getName());
        handleEvent(newConfigMap);
    }

    @Override
    public void onDelete(ConfigMap configMap, boolean deletedFinalStateUnknown) {
        log.info("Delete event received for ConfigMap {}/{}", configMap.getMetadata().getNamespace(), configMap.getMetadata().getName());
        handleEvent(configMap);
    }

    private void handleEvent(ConfigMap configMap) {
        eventHandler.handleEvent(new ConfigMapEvent(configMap, this));
    }
}
