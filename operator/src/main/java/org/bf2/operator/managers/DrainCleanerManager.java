package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.runtime.Startup;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.concurrent.atomic.AtomicBoolean;

@Startup
@ApplicationScoped
public class DrainCleanerManager {
    @Inject
    Logger log;

    @Inject
    InformerManager informerManager;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    @ConfigProperty(name = "drain.cleaner.webhook.label.key")
    String drainCleanerWebhookLabelKey;

    @ConfigProperty(name = "drain.cleaner.webhook.label.value")
    String drainCleanerWebhookLabelValue;

    private AtomicBoolean drainCleanerWebhookFound = new AtomicBoolean();

    public boolean isDrainCleanerWebhookFound() {
        return drainCleanerWebhookFound.get();
    }

    @PostConstruct
    protected void onStart() {
        FilterWatchListDeletable<ValidatingWebhookConfiguration, ValidatingWebhookConfigurationList> withLabel =
        kubernetesClient.admissionRegistration()
                .v1()
                .validatingWebhookConfigurations()
                .withLabel(drainCleanerWebhookLabelKey, drainCleanerWebhookLabelValue);

        ResourceInformer<ValidatingWebhookConfiguration> informer = resourceInformerFactory.create(ValidatingWebhookConfiguration.class, withLabel, null);
        informer.addEventHandler(new ResourceEventHandler<ValidatingWebhookConfiguration>() {
                @Override
                public void onAdd(ValidatingWebhookConfiguration obj) {
                    added(obj);
                }

                @Override
                public void onDelete(ValidatingWebhookConfiguration obj, boolean deletedFinalStateUnknown) {
                    deleted(informer.getList().isEmpty(), obj);
                }

                @Override
                public void onUpdate(ValidatingWebhookConfiguration oldObj, ValidatingWebhookConfiguration newObj) {
                    // do nothing (the OLM manages this resource)
                }
            });
    }

    void added(ValidatingWebhookConfiguration obj) {
        log.debugf("Add event received for webhook %s", obj.getMetadata().getName());
        if (drainCleanerWebhookFound.compareAndSet(false, true)) {
            log.infof("Drain cleaner webhook %s found, kafkas will be updated to have maxUnavailable=0", obj.getMetadata().getName());
            informerManager.resyncKafkas();
        }
    }

    void deleted(boolean allRemoved,
            ValidatingWebhookConfiguration obj) {
        log.debugf("Delete event received for webhook %s", obj.getMetadata().getName());
        // during an upgrade there may be more than 1 at a time, make sure there are none before toggling
        if (allRemoved && drainCleanerWebhookFound.compareAndSet(true, false)) {
            log.warnf("All drain cleaner webhooks removed, kafkas will be updated to have maxUnavailable=0 removed", obj.getMetadata().getName());
            informerManager.resyncKafkas();
        }
    }
}
