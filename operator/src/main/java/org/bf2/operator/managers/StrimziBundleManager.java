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
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.ClusterServiceVersion;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.ClusterServiceVersionBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.StrategyDeploymentSpec;
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
import java.util.LinkedHashMap;
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

    @Inject
    InformerManager informerManager;

    MixedOperation<PackageManifest, PackageManifestList, Resource<PackageManifest>> packageManifestClient;

    ResourceInformer<Subscription> subscriptionInformer;
    ResourceInformer<ClusterServiceVersion> csvInformer;

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

        this.csvInformer =
                this.resourceInformerFactory.create(ClusterServiceVersion.class,
                        this.openShiftClient.operatorHub().clusterServiceVersions().inAnyNamespace()
                                .withLabels(Map.of("operators.coreos.com/kas-strimzi-bundle.redhat-managed-kafka-operator", "")),
                        new ResourceEventHandler<ClusterServiceVersion>() {
                            @Override
                            public void onAdd(ClusterServiceVersion subscription) {
                                updateResources(null);
                            }

                            @Override
                            public void onUpdate(ClusterServiceVersion oldSubscription, ClusterServiceVersion newSubscription) {
                                if (!Objects.equals(oldSubscription.getMetadata().getGeneration(), newSubscription.getMetadata().getGeneration())) {
                                    updateResources(null);
                                }
                            }

                            @Override
                            public void onDelete(ClusterServiceVersion subscription, boolean deletedFinalStateUnknown) {
                                // nothing to do
                            }
                        });

        this.informerManager.registerKafkaInformerHandler(new ResourceEventHandler<Kafka>() {
            @Override
            public void onAdd(Kafka obj) {
                updateResources(obj);
            }

            @Override
            public void onDelete(Kafka obj, boolean deletedFinalStateUnknown) {
                updateResources(obj);
            }

            @Override
            public void onUpdate(Kafka oldObj, Kafka newObj) {
                // no action needed, until there's an ability to scale the broker count and the resource logic is dependent upon the broker count
            }
        });
    }

    private void updateResources(Kafka kafka) {
        ClusterServiceVersion csv = csvInformer.getList().stream().findFirst().orElse(null);

        if (csv == null) {
            return;
        }

        List<Kafka> kafkas = this.informerManager.getKafkas();

        // copy of the updated deployments
        List<StrategyDeploymentSpec> deployments = new ArrayList<>();
        boolean update = false;

        for (StrategyDeploymentSpec deployment : csv.getSpec().getInstall().getSpec().getDeployments()) {
            deployments.add(deployment);

            if (!deployment.getName().startsWith(StrimziManager.STRIMZI_CLUSTER_OPERATOR)) {
                continue;
            }
            if (kafka != null && !isStrimziDeploymentForKafka(deployment, kafka)) {
                continue;
            }

            // could also produce a map of strimzi to count, or now that resource considerations were removed just a set of in use versions - which should share logic
            // with the StrimziManager
            int brokerCount = kafkas.stream().filter(k -> isStrimziDeploymentForKafka(deployment, k)).mapToInt(k -> k.getSpec().getKafka().getReplicas()).sum();

            Map<String, String> annotations = new LinkedHashMap<>(Optional.ofNullable(deployment.getSpec().getTemplate().getMetadata().getAnnotations()).orElse(Map.of()));
            String replicas = null;

            if (brokerCount > 0) {
                // TODO: use annotations constants class
                replicas = annotations.remove("managedkafka.bf2.org/replicas");

                if (replicas == null) {
                    continue; // nothing to restore
                }
            } else {
                annotations.putIfAbsent("managedkafka.bf2.org/replicas", String.valueOf(deployment.getSpec().getReplicas()));
                replicas = "0";
                if (Objects.equals(0, deployment.getSpec().getReplicas())) {
                    continue;
                }
            }

            update = true;
            // a builder doesn't really work with StrategyDeploymentSpec because the buildable references do not include the nested content
            StrategyDeploymentSpec newSpec = Serialization.clone(deployment);
            newSpec.getSpec().getTemplate().getMetadata().setAnnotations(annotations);
            newSpec.getSpec().setReplicas(Integer.valueOf(replicas));
            deployments.set(deployments.size() - 1, newSpec);
        }

        if (update) {
            ClusterServiceVersionBuilder builder = new ClusterServiceVersionBuilder(csv).editSpec()
                    .editInstall()
                    .editSpec()
                    .withDeployments(deployments)
                    .endSpec()
                    .endInstall()
                    .endSpec();
            // the expectation is that nothing other than status updates are expected, so a replace should be safe
            openShiftClient.operatorHub().clusterServiceVersions().replace(builder.build());
        }
    }

    private boolean isStrimziDeploymentForKafka(StrategyDeploymentSpec deployment, Kafka k) {
        return k.getMetadata().getLabels() != null &&
                        (deployment.getName().equals(k.getMetadata().getLabels().get(strimziManager.getVersionLabel())));
    }

    /*Container operator = findOperatorContainer(deployment);
    if (operator == null) {
        continue;
    }

    ResourceRequirements resources = null;
    resources = Optional.ofNullable(annotations.remove("managedkafka.bf2.org/resources")).map(s -> Serialization.unmarshal(s, ResourceRequirements.class)).orElse(null);
    ResourceRequirementsBuilder requirementsBuilder = Optional.ofNullable(operator.getResources()).map(ResourceRequirementsBuilder::new).orElse(new ResourceRequirementsBuilder());
    // baseline resources from the upstream, could be externalized either in the fleetshard config or on the Deployment
    requirementsBuilder.addToRequests("cpu", Quantity.parse("200m"));
    requirementsBuilder.addToRequests("memory", Quantity.parse("900Mi"));
    requirementsBuilder.addToLimits("cpu", Quantity.parse("1000m"));
    requirementsBuilder.addToLimits("memory", Quantity.parse("900Mi"));
    resources = requirementsBuilder.build();
    annotations.putIfAbsent("managedkafka.bf2.org/resources", Serialization.asJson(operator.getResources()));

    private Container findOperatorContainer(StrategyDeploymentSpec deployment) {
        return deployment.getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .stream()
                .filter(container -> container.getName().startsWith(StrimziManager.STRIMZI_CLUSTER_OPERATOR))
                .findFirst().orElse(null);
    }
    */

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

            log.warnf("Subscription %s/%s has InstallPlan approval on 'Automatic'. Changing to 'Manual'.", ns, name);

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

        final String subNamespace = subscription.getMetadata().getNamespace();
        final String subName = subscription.getMetadata().getName();
        boolean approveImmediately = false;

        if (subscription.getStatus().getInstalledCSV() == null) {
            log.infof("Subscription %s/%s has no linked CSV; InstallPlan will be approved immediately", subNamespace, subName);
            approveImmediately = true;
        } else if (!this.isKafkaCrdsInstalled()) {
            log.infof("Subscription %s/%s has missing Strimzi CRDs; InstallPlan will be approved immediately", subNamespace, subName);
            approveImmediately = true;
        }

        // CSV or CRDs are not installed, nothing we can do more ... just approving installation
        if (approveImmediately) {
            this.clearMetrics();
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
                    log.infof("Subscription %s/%s will be approved after a delay", subNamespace, subName);
                    return Approval.WAITING;
                }

                this.clearMetrics();
                log.infof("Subscription %s/%s will be approved", subNamespace, subName);
                return Approval.APPROVED;
            } else {
                lastPendingInstationCheck = Long.MAX_VALUE; // reset the timestamp next time we go through above
                // covered Kafkas should be less, so if this bundle is installed, some Kafkas would be orphaned
                log.infof("Subscription %s/%s will not be approved. Covered Kafka %d/%d.",
                        subNamespace, subName, coveredKafkas, kafkas.size());
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
