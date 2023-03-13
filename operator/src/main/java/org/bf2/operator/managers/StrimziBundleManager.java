package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.CSVDescription;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageChannel;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifest;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifestList;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifestStatus;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.Subscription;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionCondition;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionSpec;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Startup
@ApplicationScoped
// excluding during smoke tests (when kafka=dev is set) running on Kubernetes without OLM
@UnlessBuildProperty(name = "kafka", stringValue = "dev", enableIfMissing = true)
public class StrimziBundleManager {

    public enum Approval {
        APPROVED,
        WAITING,
        ORPHANED,
        UNKNOWN
    }

    private static final String STRIMZI_ORPHANED_KAFKAS_METRIC = "strimzi_bundle_orphaned_kafkas";
    private static final String MANUAL = "Manual";

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

    MixedOperation<PackageManifest, PackageManifestList, Resource<PackageManifest>> packageManifestClient;

    ResourceInformer<Subscription> subscriptionInformer;

    private volatile long lastPendingInstationCheck;

    @ConfigProperty(name = "strimzi.bundle.approval-delay")
    private Duration approvalDelay;

    @PostConstruct
    protected void onStart() {

        this.packageManifestClient = this.openShiftClient.operatorHub().packageManifests();

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

        subscriptionInformer.getList()
            .stream()
            .findFirst()
            .ifPresent(this::handleSubscription);
    }

    /* test */ public synchronized void handleSubscription(Subscription subscription) {
        if (!this.isInstallPlanApprovalAsManual(subscription)) {
            final String ns = subscription.getMetadata().getNamespace();
            final String name = subscription.getMetadata().getName();

            log.warnf("Subscription %s/%s has InstallPlan approval on 'Automatic'. Changing to 'Manual'.",ns, name);

            this.openShiftClient.operatorHub()
                .subscriptions()
                .inNamespace(ns)
                .withName(name)
                .edit(s -> {
                    s.getSpec().setInstallPlanApproval(MANUAL);
                    return s;
                });

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
                    Approval approval = this.approveInstallation(subscription);
                    if (approval == Approval.APPROVED) {
                        this.openShiftClient.operatorHub().installPlans()
                                .inNamespace(subscription.getStatus().getInstallPlanRef().getNamespace())
                                .withName(subscription.getStatus().getInstallPlanRef().getName())
                                .edit(ip -> {
                                    ip.getSpec().setApproved(true);
                                    return ip;
                                });
                    }
                    this.updateStatus(approval);
                    log.infof("Subscription %s/%s approval = %s", subscription.getMetadata().getNamespace(), subscription.getMetadata().getName(), approval);
                } else {
                    log.warnf("InstallPlan reference missing in Subscription %s/%s",
                            subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());
                }
            } else if (!Objects.equals(subscription.getStatus().getCurrentCSV(), subscription.getStatus().getInstalledCSV())) {
                // approved, but not yet installed
                if (this.strimziManager.getStrimziPendingInstallationVersions().isEmpty()) {
                    List<String> strimziVersions = getStrimziVersions(subscription);

                    if (strimziVersions != null && !strimziVersions.isEmpty()) {
                        this.strimziManager.setStrimziPendingInstallationVersions(strimziVersions);
                    }
                }
            } else {
                // approved and current == installed, nothing is pending
                this.strimziManager.clearStrimziPendingInstallationVersions();
            }
        } else {
            // it should never happen
        }
    }

    private Approval approveInstallation(Subscription subscription) {
        List<String> strimziVersions = getStrimziVersions(subscription);

        if (strimziVersions == null || strimziVersions.isEmpty()) {
            return Approval.UNKNOWN;
        }

        // CRDs are not installed, nothing we can do more ... just approving installation
        if (!this.isKafkaCrdsInstalled()) {
            this.clearMetrics();
            log.infof("Subscription %s/%s will be approved", subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());
            return Approval.APPROVED;
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
                boolean changed = this.strimziManager.setStrimziPendingInstallationVersions(strimziVersions);

                long currentTimeMillis = System.currentTimeMillis();
                if (changed || lastPendingInstationCheck == Long.MAX_VALUE) {
                    lastPendingInstationCheck = currentTimeMillis;
                }
                if (currentTimeMillis - lastPendingInstationCheck < approvalDelay.toMillis()) {
                    log.infof("Subscription %s/%s will be approved after a delay", subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());
                    return Approval.WAITING;
                }

                this.clearMetrics();
                log.infof("Subscription %s/%s will be approved", subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());
                return Approval.APPROVED;
            } else {
                lastPendingInstationCheck = Long.MAX_VALUE; // reset the timestamp next time we go through above
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
                return Approval.ORPHANED;
            }
        }
    }

    private List<String> getStrimziVersions(Subscription subscription) {
        PackageManifest packageManifest = this.packageManifestClient.inNamespace(subscription.getMetadata().getNamespace())
                .withName(subscription.getSpec().getName())
                .get();

        if (packageManifest == null) {
            return Collections.emptyList();
        }
        // avoiding the nullability of one of the status fields along the chain due to Kubernetes not updating
        // the PackageManifest on time when the fleetshard operator starts to take care of the approval
        // This way avoid a race condition raising an NPE
        String strimziVersionsJson =
                Optional.of(packageManifest)
                        .map(PackageManifest::getStatus)
                        .map(PackageManifestStatus::getChannels)
                        .map(packageChannels -> !packageChannels.isEmpty() ? packageChannels.get(0) : null)
                        .map(PackageChannel::getCurrentCSVDesc)
                        .map(CSVDescription::getAnnotations)
                        .map(annotations -> annotations.get("strimziVersions"))
                        .orElse(null);

        if (strimziVersionsJson == null) {
            log.warnf("No Strimzi versions found in PackageManifest %s/%s yet.",
                    packageManifest.getMetadata().getNamespace(), packageManifest.getMetadata().getName());
            return Collections.emptyList();
        }
        List<String> strimziVersions = new ArrayList<>();
        try {
            strimziVersions = Serialization.jsonMapper().readValue(strimziVersionsJson, List.class);
            log.debugf("PackageManifest %s/%s Strimzi versions = %s",
                    packageManifest.getMetadata().getNamespace(), packageManifest.getMetadata().getName(), strimziVersions);
        } catch (Exception e) {
            log.errorf(e, "Error processing Strimzi versions in PackageManifest %s/%s",
                    packageManifest.getMetadata().getNamespace(), packageManifest.getMetadata().getName());
        }
        return strimziVersions;
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
     * @param approval the status of the approval
     */
    private void updateStatus(Approval approval) {
        ManagedKafkaAgent resource = this.agentClient.getByName(this.agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
        if (resource != null && resource.getStatus() != null) {
            List<ManagedKafkaCondition> conditions = resource.getStatus().getConditions();

            ManagedKafkaCondition bundleReadyCondition = ConditionUtils.findManagedKafkaCondition(conditions, ManagedKafkaCondition.Type.StrimziBundleReady).orElse(null);
            ManagedKafkaCondition.Reason reason = ManagedKafkaCondition.Reason.OrphanedKafkas;
            ManagedKafkaCondition.Status status = ManagedKafkaCondition.Status.False;

            if (approval != Approval.ORPHANED) {
                if (bundleReadyCondition == null) {
                    return;
                }
                conditions.remove(bundleReadyCondition);
            } else {
                if (bundleReadyCondition == null) {
                    bundleReadyCondition = ConditionUtils.buildCondition(ManagedKafkaCondition.Type.StrimziBundleReady, status);
                    bundleReadyCondition.reason(reason);
                    conditions.add(bundleReadyCondition);
                } else {
                    ConditionUtils.updateConditionStatus(bundleReadyCondition, status, reason, null);
                }
            }
            this.agentClient.replaceStatus(resource);
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
        return Optional.ofNullable(subscription.getSpec())
            .map(SubscriptionSpec::getInstallPlanApproval)
            .map(MANUAL::equals)
            .orElse(false);
    }

    void setApprovalDelay(Duration approvalDelay) {
        this.approvalDelay = approvalDelay;
    }

    Duration getApprovalDelay() {
        return approvalDelay;
    }
}
