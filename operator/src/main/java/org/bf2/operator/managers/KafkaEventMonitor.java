package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@Startup
@ApplicationScoped
public class KafkaEventMonitor {

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @PostConstruct
    protected void onStart() {
        startWatch();
    }

    private void startWatch() {
        log.info("Starting event monitor..");
        kubernetesClient.events().v1().events().inAnyNamespace().watch(new KafkaEventWatcher());
    }

    class KafkaEventWatcher implements Watcher<Event> {
        @Override
        public void eventReceived(Action action, Event obj) {
            if (action != Action.DELETED) {
                logEvent(obj);
            }
        }

        @Override
        public void onClose(WatcherException cause) {
            if (cause.isHttpGone()) {
                log.debug("Event Watch restarting due to previous watch was closed with http gone");
                startWatch();
              } else {
                // shouldn't happen, but it means the watch won't restart
                log.warn("Event Watch closing with exception, failed to restart", cause);
              }
        }

        private boolean isKafkaEvent(Event obj) {
            return obj.getMetadata().getNamespace().startsWith("kafka-") || obj.getMetadata().getNamespace().startsWith("mk-");
        }

        private void logEvent(Event obj) {
            if (isKafkaEvent(obj) && !obj.getType().equals("Normal")) {
                log.warnf("event received: %s/%s/%s/%s", obj.getMetadata().getNamespace(), obj.getType(), obj.getReason(), obj.getNote());
            }
        }
    }
}
