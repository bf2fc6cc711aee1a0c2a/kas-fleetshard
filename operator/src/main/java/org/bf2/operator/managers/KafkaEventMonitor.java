package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.quarkus.runtime.Startup;
import org.bf2.common.OperandUtils;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Map;

@Startup
@ApplicationScoped
public class KafkaEventMonitor {

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    ResourceInformer<Namespace> namespaceInformer;

    @PostConstruct
    protected void onStart() {
        FilterWatchListDeletable<Namespace, NamespaceList> namespaceFilter = kubernetesClient.namespaces()
                .withLabels(Map.of(OperandUtils.MANAGED_BY_LABEL, OperandUtils.FLEETSHARD_OPERATOR_NAME));
        namespaceInformer = resourceInformerFactory.create(Namespace.class, namespaceFilter, null);
        startWatch();
    }

    private void startWatch() {
        log.info("Starting event monitor..");
        kubernetesClient.events().v1().events().inAnyNamespace().watch(new KafkaEventWatcher());
    }

    class KafkaEventWatcher implements Watcher<Event> {
        private static final String NORMAL = "Normal";

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
            return namespaceInformer.getList().stream()
                    .anyMatch(ns -> ns.getMetadata().getName().equals(obj.getMetadata().getNamespace()));
        }

        private void logEvent(Event obj) {
            if (!NORMAL.equals(obj.getType()) && isKafkaEvent(obj)) {
                log.warnf("event received: %s/%s/%s/%s", obj.getMetadata().getNamespace(), obj.getType(), obj.getReason(), obj.getNote());
            }
        }
    }
}
