package org.bf2.operator;

import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.runtime.Startup;
import org.bf2.common.ResourceInformer;
import org.bf2.operator.operands.KafkaCluster;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@Startup
@ApplicationScoped
public class DrainCleanerManager {
    @Inject
    Logger log;

    @Inject
    InformerManager informerManager;

    @Inject
    KafkaCluster kafkaCluster;

    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "drain.cleaner.webhook.label.key")
    String drainCleanerWebhookLabelKey;

    @ConfigProperty(name = "drain.cleaner.webhook.label.value")
    String drainCleanerWebhookLabelValue;

    private volatile boolean drainCleanerWebhookFound;

    public boolean isDrainCleanerWebhookFound() {
        return drainCleanerWebhookFound;
    }

    @PostConstruct
    protected void onStart() {
        FilterWatchListDeletable<ValidatingWebhookConfiguration, ValidatingWebhookConfigurationList> withLabel =
        kubernetesClient.admissionRegistration()
                .v1()
                .validatingWebhookConfigurations()
                .withLabel(drainCleanerWebhookLabelKey, drainCleanerWebhookLabelValue);

        ResourceInformer.start(ValidatingWebhookConfiguration.class,
            withLabel,
            new ResourceEventHandler<ValidatingWebhookConfiguration>() {
                @Override
                public void onAdd(ValidatingWebhookConfiguration obj) {
                    log.debugf("Add event received for webhook %s", obj.getMetadata().getName());
                    drainCleanerWebhookFound = true;
                    informerManager.resyncKafkas();
                }

                @Override
                public void onDelete(ValidatingWebhookConfiguration obj, boolean deletedFinalStateUnknown) {
                    log.debugf("Delete event received for webhook %s", obj.getMetadata().getName());
                    drainCleanerWebhookFound = false;
                    informerManager.resyncKafkas();
                }

                @Override
                public void onUpdate(ValidatingWebhookConfiguration oldObj, ValidatingWebhookConfiguration newObj) {
                    // do nothing (the OLM manages this resource)
                }
            });
    }
}
