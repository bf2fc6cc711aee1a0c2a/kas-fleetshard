package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@Startup
@ApplicationScoped
public class EventMonitor {

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @PostConstruct
    protected void onStart() {
        log.info("Starting event monitor..");
        kubernetesClient.events().v1().events().inAnyNamespace().inform(new ResourceEventHandler<Event>() {
            @Override
            public void onUpdate(Event oldObj, Event obj) {
                logEvent(obj);
            }
            @Override
            public void onDelete(Event obj, boolean deletedFinalStateUnknown) {
            }
            @Override
            public void onAdd(Event obj) {
                logEvent(obj);
            }
            private void logEvent(Event obj) {
                if (isKafkaEvent(obj) && !obj.getType().equals("Normal")) {
                    log.warnf("event received: %s/%s/%s/%s", obj.getMetadata().getNamespace(), obj.getType(), obj.getReason(), obj.getNote());
                }
            }
        });
    }

    private boolean isKafkaEvent(Event obj) {
        return obj.getMetadata().getNamespace().startsWith("kafka-") || obj.getMetadata().getNamespace().startsWith("mk-");
    }
}
