package org.bf2.operator.managers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifest;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.InstallPlan;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.Subscription;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionCondition;
import io.fabric8.openshift.client.OpenShiftClient;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.arc.properties.UnlessBuildProperty;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.ConditionUtils;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Startup
@ApplicationScoped
// excluding during smoke tests (when kafka=dev is set) running on Kubernetes without OLM
@UnlessBuildProperty(name = "kafka", stringValue = "dev", enableIfMissing = true)
public class StrimziBundleManager {

    private static final String STRIMZI_ORPHANED_KAFKAS_METRIC = "strimzi_bundle_orphaned_kafkas";

    @Inject
    Logger log;

    @Inject
    OpenShiftClient openShiftClient;

    @Inject
    KafkaResourceClient kafkaClient;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    @Inject
    StrimziManager strimziManager;

    @Inject
    ManagedKafkaAgentResourceClient agentClient;

    @Inject
    MeterRegistry meterRegistry;

    // TODO: this raw custom resource client can be removed once we have Quarkus + SDK extension using the fabric8 5.5.0
    // which supports the package manifests API out of the box
    MixedOperation<PackageManifest, KubernetesResourceList<PackageManifest>, Resource<PackageManifest>> packageManifestClient;

    ResourceInformer<Subscription> subscriptionInformer;

    @PostConstruct
    protected void onStart() {

        this.packageManifestClient = this.createPackageManifestClient();

        this.subscriptionInformer =
                this.resourceInformerFactory.create(Subscription.class,
                        this.openShiftClient.operatorHub().subscriptions().inAnyNamespace()
                                .withLabels(Map.of("app.kubernetes.io/part-of", "managed-kafka", "app.kubernetes.io/component", "strimzi-bundle")),
                        new ResourceEventHandler<Subscription>() {
                            @Override
                            public void onAdd(Subscription subscription) {
                                handleSubscription(subscription);
                            }

                            @Override
                            public void onUpdate(Subscription oldSubscription, Subscription newSubscription) {
                                handleSubscription(newSubscription);
                            }

                            @Override
                            public void onDelete(Subscription subscription, boolean deletedFinalStateUnknown) {
                                // nothing to do
                            }
                        });
    }

    @Scheduled(every = "{strimzi.bundle.interval}", delay = 1, concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void handleSubscriptionLoop() {
        log.debugf("Strimzi bundle Subscription periodic check");
        Subscription subscription =
                this.subscriptionInformer.getList().size() > 0 ? this.subscriptionInformer.getList().get(0) : null;
        if (subscription != null) {
            this.handleSubscription(subscription);
        }
    }

    /* test */ public synchronized void handleSubscription(Subscription subscription) {
        if (!this.isInstallPlanApprovalAsManual(subscription)) {
            log.warnf("Subscription %s/%s has InstallPlan approval on 'Automatic'. Skipping approval process.",
                    subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());
            return;
        }
        if (subscription.getStatus() != null) {
            Optional<SubscriptionCondition> conditionOptional =
                    subscription.getStatus().getConditions()
                            .stream()
                            .filter(c -> c.getType() != null && "InstallPlanPending".equals(c.getType()) &&
                                    c.getReason() != null && "RequiresApproval".equals(c.getReason()))
                            .findFirst();

            // waiting for approval
            if (conditionOptional.isPresent()) {
                log.infof("Subscription %s/%s waiting for approval", subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());

                if (subscription.getStatus().getInstallPlanRef() != null) {
                    InstallPlan installPlan =
                            this.openShiftClient.operatorHub().installPlans()
                                    .inNamespace(subscription.getStatus().getInstallPlanRef().getNamespace())
                                    .withName(subscription.getStatus().getInstallPlanRef().getName())
                                    .get();

                    boolean approved = this.approveInstallation(subscription);
                    if (approved) {
                        installPlan.getSpec().setApproved(true);
                        this.openShiftClient.operatorHub().installPlans().inNamespace(installPlan.getMetadata().getNamespace()).createOrReplace(installPlan);
                    }
                    this.updateStatus(approved);
                    log.infof("Subscription %s/%s approved = %s", subscription.getMetadata().getNamespace(), subscription.getMetadata().getName(), approved);
                } else {
                    log.warnf("InstallPlan reference missing in Subscription %s/%s",
                            subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());
                }
            } else {
                // not waiting for approval, nothing to do
            }
        } else {
            // it should never happen
        }
    }

    private boolean approveInstallation(Subscription subscription) {
        PackageManifest packageManifest = this.packageManifestClient.inNamespace(subscription.getMetadata().getNamespace())
                .withName(subscription.getSpec().getName())
                .get();

        List<String> strimziVersions = this.strimziVersionsFromPackageManifest(packageManifest);

        if (strimziVersions == null || strimziVersions.isEmpty()) {
            return false;
        }

        // CRDs are not installed, nothing we can do more ... just approving installation
        if (!this.isKafkaCrdsInstalled()) {
            this.clearMetrics();
            log.infof("Subscription %s/%s will be approved", subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());
            return true;
        } else {
            List<Kafka> kafkas = this.kafkaClient.list();
            // get Kafkas that could be orphaned because handled by a Strimzi version that could be removed from the proposed bundle
            Map<String, List<Kafka>> orphanedKafkas = kafkas.stream()
                    .filter(k -> k.getMetadata().getLabels() != null &&
                            k.getMetadata().getLabels().containsKey(this.strimziManager.getVersionLabel()) &&
                            !strimziVersions.contains(k.getMetadata().getLabels().get(this.strimziManager.getVersionLabel())))
                    .collect(Collectors.groupingBy(k -> k.getMetadata().getLabels().get(this.strimziManager.getVersionLabel())));

            int coveredKafkas = kafkas.size() - orphanedKafkas.size();

            // the Strimzi versions available in the bundle cover all the Kafka instances running
            if (coveredKafkas == kafkas.size()) {
                this.clearMetrics();
                log.infof("Subscription %s/%s will be approved", subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());
                return true;
            } else {
                // covered Kafkas should be less, so if this bundle is installed, some Kafkas would be orphaned
                log.infof("Subscription %s/%s will not be approved. Covered Kafka %d/%d.",
                        subscription.getMetadata().getNamespace(), subscription.getMetadata().getName(), coveredKafkas, kafkas.size());
                log.infof("Kafka instances not covered per Strimzi version:");
                for (Map.Entry<String, List<Kafka>> e : orphanedKafkas.entrySet()) {
                    meterRegistry.gaugeCollectionSize(STRIMZI_ORPHANED_KAFKAS_METRIC, Tags.of("strimzi", e.getKey()), e.getValue());
                    List<String> kafkaNames = e.getValue().stream()
                            .map(k -> k.getMetadata().getNamespace() + "/" + k.getMetadata().getName())
                            .collect(Collectors.toList());
                    log.infof("\t- %s -> %s", e.getKey(), kafkaNames);
                }
                return false;
            }
        }
    }

    private List<String> strimziVersionsFromPackageManifest(PackageManifest packageManifest) {
        String strimziVersionsJson = packageManifest.getStatus().getChannels().get(0).getCurrentCSVDesc().getAnnotations().get("strimziVersions");
        ObjectMapper mapper = new ObjectMapper();
        List<String> strimziVersions = new ArrayList<>();
        try {
            strimziVersions = mapper.readValue(strimziVersionsJson, List.class);
            log.debugf("PackageManifest %s/%s Strimzi versions = %s",
                    packageManifest.getMetadata().getNamespace(), packageManifest.getMetadata().getName(), strimziVersions);
        } catch (Exception e) {
            log.errorv(e, "Error processing Strimzi versions in PackageManifest %s/%s",
                    packageManifest.getMetadata().getNamespace(), packageManifest.getMetadata().getName());
        }
        return strimziVersions;
    }

    private MixedOperation<PackageManifest, KubernetesResourceList<PackageManifest>, Resource<PackageManifest>> createPackageManifestClient() {
        CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                .withKind(HasMetadata.getKind(PackageManifest.class))
                .withGroup(HasMetadata.getGroup(PackageManifest.class))
                .withScope("Namespaced")
                .withVersion(HasMetadata.getApiVersion(PackageManifest.class))
                //.withPlural(HasMetadata.getPlural(PackageManifest.class))
                .withPlural("packagemanifests")
                .build();

        return this.openShiftClient.customResources(ctx, PackageManifest.class, null);
    }

    /**
     * @return if the Strimzi/Kafka related CRDs are installed
     */
    private boolean isKafkaCrdsInstalled() {
        List<CustomResourceDefinition> crds =
                this.openShiftClient.apiextensions().v1().customResourceDefinitions()
                        .withLabels(Map.of("app", "strimzi"))
                        .list()
                        .getItems();
        return !crds.isEmpty();
    }

    /**
     * Update status of ManagedKafkaAgent resource about approval of Strimzi bundle installation
     * NOTE: it creates a condition if Strimzi bundle installation was not approved. The condition is taken out if approved.
     *
     * @param approved if the Strimzi bundle installation was approved
     */
    private void updateStatus(boolean approved) {
        ManagedKafkaAgent resource = this.agentClient.getByName(this.agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
        if (resource != null && resource.getStatus() != null) {
            List<ManagedKafkaCondition> conditions = resource.getStatus().getConditions();

            ManagedKafkaCondition bundleReadyCondition = ConditionUtils.findManagedKafkaCondition(conditions, ManagedKafkaCondition.Type.StrimziBundleReady).orElse(null);
            if (bundleReadyCondition == null) {
                if (approved) {
                    return;
                } else {
                    bundleReadyCondition = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.StrimziBundleReady, ManagedKafkaCondition.Status.False);
                    bundleReadyCondition.setReason(ManagedKafkaCondition.Reason.OrphanedKafkas.name());
                    conditions.add(bundleReadyCondition);
                }
            } else {
                if (approved) {
                    conditions.remove(bundleReadyCondition);
                } else {
                    ConditionUtils.updateConditionStatus(bundleReadyCondition, ManagedKafkaCondition.Status.False, ManagedKafkaCondition.Reason.OrphanedKafkas, null);
                }
            }
            this.agentClient.updateStatus(resource);
        }
    }

    private void clearMetrics() {
        List<Meter> toRemove = meterRegistry.getMeters().stream()
                .filter(m -> STRIMZI_ORPHANED_KAFKAS_METRIC.equals(m.getId().getName()))
                .collect(Collectors.toList());

        for (Meter m : toRemove) {
            meterRegistry.remove(m.getId());
        }
    }

    private boolean isInstallPlanApprovalAsManual(Subscription subscription) {
        return subscription.getSpec() != null && subscription.getSpec().getInstallPlanApproval() != null &&
                "Manual".equals(subscription.getSpec().getInstallPlanApproval());
    }
}
