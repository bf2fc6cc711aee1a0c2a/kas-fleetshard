package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.runtime.Startup;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.ManagedKafkaKeys.Annotations;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatus;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatusBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Startup
@ApplicationScoped
public class StrimziManager {

    public static final String STRIMZI_CLUSTER_OPERATOR = "strimzi-cluster-operator";
    public static final String STRIMZI_PAUSE_RECONCILE_ANNOTATION = "strimzi.io/pause-reconciliation";
    public static final String KAFKA_IMAGES_ENVVAR = "STRIMZI_KAFKA_IMAGES";

    // concurrent hash maps don't like null values, so we'll use this instead
    private static final ComponentVersions EMPTY_STATUS = new ComponentVersions(new StrimziVersionStatus(), Collections.emptyMap());

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ManagedKafkaAgentResourceClient agentClient;

    @Inject
    protected InformerManager informerManager;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    static class ComponentVersions {
        final StrimziVersionStatus strimziVersion;
        final Map<String, String> relatedImages;

        ComponentVersions(StrimziVersionStatus strimziVersion, Map<String, String> relatedImages) {
            this.strimziVersion = strimziVersion;
            this.relatedImages = relatedImages;
        }

        public StrimziVersionStatus getStrimziVersion() {
            return strimziVersion;
        }
    }

    private Map<String, ComponentVersions> strimziVersions = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<String, ComponentVersions> strimziPendingInstallationVersions = new ConcurrentHashMap<>();

    // this configuration needs to match with the STRIMZI_CUSTOM_RESOURCE_SELECTOR env var in the Strimzi Deployment(s)
    @ConfigProperty(name = "strimzi.version.label", defaultValue = ManagedKafkaKeys.Labels.STRIMZI_VERSION)
    protected String versionLabel;

    @PostConstruct
    protected void onStart() {
        // update the initial deployments as a single operation
        FilterWatchListDeletable<Deployment, DeploymentList> deployments = this.kubernetesClient.apps().deployments().inAnyNamespace().withLabels(Map.of("app.kubernetes.io/part-of", "managed-kafka"));
        for (Deployment deployment : deployments.list().getItems()) {
            if (isStrimziDeployment(deployment)) {
                log.debugf("Adding Deployment %s/%s", deployment.getMetadata().getNamespace(),
                        deployment.getMetadata().getName());
                updateStrimziVersion(deployment);
            }
        }
        updateStatus();
        this.resourceInformerFactory.create(Deployment.class,
                deployments,
                new ResourceEventHandler<Deployment>() {
                    @Override
                    public void onAdd(Deployment deployment) {
                        if (isStrimziDeployment(deployment)) {
                            log.debugf("Add/update event received for Deployment %s/%s",
                                    deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
                            updateStrimziVersion(deployment);
                            updateStatus();
                        }
                    }

                    @Override
                    public void onUpdate(Deployment oldDeployment, Deployment newDeployment) {
                        onAdd(newDeployment);
                    }

                    @Override
                    public void onDelete(Deployment deployment, boolean deletedFinalStateUnknown) {
                        if (isStrimziDeployment(deployment)) {
                            log.debugf("Delete event received for Deployment %s/%s",
                                    deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
                            deleteStrimziVersion(deployment);
                            updateStatus();
                        }
                    }
                });
    }

    private boolean isStrimziDeployment(Deployment deployment) {
        return deployment.getMetadata().getName().startsWith(STRIMZI_CLUSTER_OPERATOR);
    }

    private void updateStatus() {
        List<StrimziVersionStatus> versions = this.strimziVersions.values().stream().map(ComponentVersions::getStrimziVersion).collect(Collectors.toList());
        // create the Kafka informer only when a Strimzi bundle is installed (aka at least one available version)
        if (!versions.isEmpty()) {
            informerManager.createKafkaInformer();
        }

        ManagedKafkaAgent resource = agentClient.getByName(agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
        if (resource != null && resource.getStatus() != null) {
            List<StrimziVersionStatus> existing = resource.getStatus().getStrimzi();
            if (!versions.equals(existing)) {
                log.debugf("Updating Strimzi versions %s", versions);
                resource.getStatus().setStrimzi(versions);
                agentClient.replaceStatus(resource);
                // version changes should sync the managed kafkas
                if (existing == null || !toVersionKeySet(versions).equals(toVersionKeySet(existing))) {
                    informerManager.resyncManagedKafka();
                }
            }
        }
    }

    private Set<String> toVersionKeySet(List<StrimziVersionStatus> versions) {
        Set<String> keys = versions.stream().map(StrimziVersionStatus::getVersion).collect(Collectors.toCollection(HashSet::new));
        keys.addAll(strimziPendingInstallationVersions.keySet());
        return keys;
    }

    /* test */ public void updateStrimziVersion(Deployment deployment) {
        List<String> kafkaVersions = readKafkaVersions(deployment);
        List<String> kafkaIbpVersions = kafkaVersions.stream().map(AbstractKafkaCluster::getKafkaIbpVersion).collect(Collectors.toCollection(ArrayList::new));
        Map<String, String> relatedImages = readRelatedImagesAnnotation(deployment);

        StrimziVersionStatus strimziVersion = new StrimziVersionStatusBuilder()
                .withVersion(deployment.getMetadata().getName())
                .withKafkaVersions(kafkaVersions)
                .withKafkaIbpVersions(kafkaIbpVersions)
                .withReady(Readiness.isDeploymentReady(deployment))
                .build();

        this.strimziVersions.put(deployment.getMetadata().getName(),
                new ComponentVersions(strimziVersion, relatedImages));
    }

    List<String> readKafkaVersions(Deployment deployment) {
        return readAnnotation(deployment, Annotations.KAFKA_IMAGES)
            .or(() -> readVersionEnvVar(deployment))
            .map(this::parseKafkaVersions)
            .orElseGet(Collections::emptyList);
    }

    Optional<String> readVersionEnvVar(Deployment deployment) {
        return deployment.getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .stream()
                .filter(container -> container.getName().startsWith(STRIMZI_CLUSTER_OPERATOR))
                .flatMap(container -> container.getEnv().stream())
                .filter(ev -> ev.getName().equals(KAFKA_IMAGES_ENVVAR))
                .map(EnvVar::getValue)
                .filter(Objects::nonNull)
                .findFirst();
    }

    List<String> parseKafkaVersions(String versions) {
        return Arrays.stream(versions.split("\n"))
                .map(entry -> entry.split("="))
                .map(entry -> entry[0])
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @SuppressWarnings("unchecked")
    Map<String, String> readRelatedImagesAnnotation(Deployment deployment) {
        return readAnnotation(deployment, Annotations.RELATED_IMAGES)
                .map(value -> Serialization.unmarshal(value, Map.class))
                .orElseGet(Collections::emptyMap);
    }

    Optional<String> readAnnotation(Deployment deployment, String annotationName) {
        return Optional.of(deployment.getSpec().getTemplate())
                .map(PodTemplateSpec::getMetadata)
                .map(ObjectMeta::getAnnotations)
                .map(annotations -> annotations.get(annotationName));
    }

    /* test */ public void deleteStrimziVersion(Deployment deployment) {
        ComponentVersions removed = this.strimziVersions.remove(deployment.getMetadata().getName());
        if (removed != null) {
            // if we will resurrect the version, then just move the info into pending.
            // this approach does leave open a small window for the fso to be restarted after the
            // original deployment goes away, but before the next one is deployed.
            //
            // the only guard against that involves a lot more refinement of this logic
            // including extracting the status info from the csv in the bundle manager
            this.strimziPendingInstallationVersions.computeIfPresent(deployment.getMetadata().getName(),
                    (k, s) -> new ComponentVersions(new StrimziVersionStatusBuilder(removed.strimziVersion).withReady(false).build(), Map.copyOf(removed.relatedImages)));
        }
    }

    /**
     * Upgrade the Strimzi version of the cluster operator used to handle the Kafka instance by taking it from the ManagedKafka resource
     *
     * @param managedKafka ManagedKafka instance to get the Kafka version
     * @param kafkaCluster Kafka cluster operand
     * @param kafkaBuilder KafkaBuilder instance to update the Kafka inter broker protocol version on the cluster
     */
    public void upgradeStrimziVersion(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster, KafkaBuilder kafkaBuilder) {
        Map<String, String> labels = kafkaBuilder
                .buildMetadata()
                .getLabels();

        Map<String, String> annotations = kafkaBuilder
                .buildMetadata()
                .getAnnotations();

        log.infof("Strimzi change from %s to %s",
                this.currentStrimziVersion(managedKafka), managedKafka.getSpec().getVersions().getStrimzi());
        // Kafka cluster is running and ready --> pause reconcile or at the end of upgrade remove pause reason annotation
        if (kafkaCluster.isReadyNotUpdating(managedKafka)) {
            if (!isPauseReasonStrimziUpdate(annotations)) { // if already paused for another reason, we'll override to proceed with the upgrade
                pauseReconcile(managedKafka, annotations);
                annotations.put(Annotations.STRIMZI_PAUSE_REASON, ManagedKafkaCondition.Reason.StrimziUpdating.name().toLowerCase());
            } else if (!"true".equals(annotations.get(STRIMZI_PAUSE_RECONCILE_ANNOTATION))) {
                annotations.remove(Annotations.STRIMZI_PAUSE_REASON);
            } // else don't remove the pause reason - strimzi has not reconciled yet
        // Kafka cluster reconcile is paused because of Strimzi updating --> apply version from spec to handover and unpause to restart reconcile
        } else if (kafkaCluster.isReconciliationPaused(managedKafka)) {
            if (isPauseReasonStrimziUpdate(annotations)) {
                labels.put(this.versionLabel, managedKafka.getSpec().getVersions().getStrimzi());
                unpauseReconcile(managedKafka, annotations);
            } else if (annotations.get(Annotations.STRIMZI_PAUSE_REASON) == null) {
                // defensively assume we're updating
                annotations.put(Annotations.STRIMZI_PAUSE_REASON, ManagedKafkaCondition.Reason.StrimziUpdating.name().toLowerCase());
            } // else we don't know why we're paused
        }

        kafkaBuilder
                .editMetadata()
                    .withLabels(labels)
                    .withAnnotations(annotations)
                .endMetadata();
    }

    /**
     * Compare current Strimzi version from the Kafka custom resource with the requested one in the ManagedKafka spec
     * in order to return if a version change happened
     *
     * @param managedKafka ManagedKafka instance
     * @return if a Strimzi version change was requested
     */
    public boolean hasStrimziChanged(ManagedKafka managedKafka) {
        log.debugf("requestedStrimziVersion = %s", managedKafka.getSpec().getVersions().getStrimzi());
        return !this.currentStrimziVersion(managedKafka).equals(managedKafka.getSpec().getVersions().getStrimzi());
    }

    /**
     * Returns the current Strimzi version for the Kafka instance
     * It comes directly from the Kafka custom resource label or from the ManagedKafka in case of creation
     *
     * @param managedKafka ManagedKafka instance
     * @return current Strimzi version for the Kafka instance
     */
    public String currentStrimziVersion(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        // on first time Kafka resource creation, we take the Strimzi version from the ManagedKafka resource spec
        String kafkaStrimziVersion = kafka != null && kafka.getMetadata().getLabels() != null && kafka.getMetadata().getLabels().containsKey(this.versionLabel) ?
                kafka.getMetadata().getLabels().get(this.versionLabel) :
                managedKafka.getSpec().getVersions().getStrimzi();
        log.debugf("currentStrimziVersion = %s", kafkaStrimziVersion);
        return kafkaStrimziVersion;
    }

    /**
     * Pause reconcile of the Kafka custom resource corresponding to the ManagedKafka one
     * by adding the pause-reconciliation annotation on the provided annotations list
     *
     * @param managedKafka ManagedKafka instance
     * @param annotations Kafka custom resource annotations on which adding the pause
     */
    private void pauseReconcile(ManagedKafka managedKafka, Map<String, String> annotations) {
        if (!annotations.containsKey(STRIMZI_PAUSE_RECONCILE_ANNOTATION)) {
            log.debugf("Pause reconcile for %s", managedKafka.getMetadata().getName());
            annotations.put(STRIMZI_PAUSE_RECONCILE_ANNOTATION, "true");
        }
    }

    /**
     * Unpause reconcile of the Kafka custom resource corresponding to the ManagedKafka one
     * by removing the pause-reconciliation annotation from the provided annotations list
     *
     * @param managedKafka ManagedKafka instance
     * @param annotations Kafka custom resource annotations from which removing the pause
     */
    private void unpauseReconcile(ManagedKafka managedKafka, Map<String, String> annotations) {
        if (annotations.containsKey(STRIMZI_PAUSE_RECONCILE_ANNOTATION)) {
            log.debugf("Unpause reconcile for %s", managedKafka.getMetadata().getName());
            annotations.remove(STRIMZI_PAUSE_RECONCILE_ANNOTATION);
        }
    }

    /**
     * Check if Kafka reconcile is paused due to Strimzi updating request
     *
     * @param annotations Kafka custom resource annotations from which checking the pause reason
     * @return if pausing is due to Strimzi updating
     */
    public static boolean isPauseReasonStrimziUpdate(Map<String, String> annotations) {
        return ManagedKafkaCondition.Reason.StrimziUpdating.name()
                .toLowerCase()
                .equals(annotations.get(Annotations.STRIMZI_PAUSE_REASON));
    }

    private Kafka cachedKafka(ManagedKafka managedKafka) {
        return this.informerManager.getLocalKafka(AbstractKafkaCluster.kafkaClusterNamespace(managedKafka), AbstractKafkaCluster.kafkaClusterName(managedKafka));
    }

    /**
     * @param strimziVersion the strimzi version
     * @return the corresponding status, which may be from a version that will be removed
     */
    public StrimziVersionStatus getStrimziVersion(String strimziVersion) {
        ComponentVersions result = this.strimziVersions.get(strimziVersion);
        if (result == null) {
            result = this.strimziPendingInstallationVersions.get(strimziVersion);
            if (result == EMPTY_STATUS) {
                result = null;
            }
        }
        return result != null ? result.strimziVersion : null;
    }

    public String getRelatedImage(String strimziVersion, String key) {
        return strimziVersions.getOrDefault(strimziVersion, EMPTY_STATUS).relatedImages.get(key);
    }

    /**
     * @return list of installed Strimzi versions with related readiness status. it will not
     * include versions that may be removed or non-common versions that are pending installation.
     * Common versions are those found in both an old and a new CSV.
     */
    public List<StrimziVersionStatus> getStrimziVersions() {
        Map<String, ComponentVersions> nextVersions = new HashMap<>(strimziPendingInstallationVersions);
        Map<String, ComponentVersions> result = this.strimziVersions;
        // if there are pending versions, then merge the lists by keeping only the valid next
        if (!nextVersions.isEmpty()) {
            result = nextVersions;
            for (Iterator<Map.Entry<String, ComponentVersions>> iter = result.entrySet().iterator(); iter.hasNext();) {
                Map.Entry<String, ComponentVersions> entry = iter.next();
                ComponentVersions live = this.strimziVersions.get(entry.getKey());
                if (live != null) {
                    entry.setValue(live);
                } else if (entry.getValue() == EMPTY_STATUS) {
                    iter.remove();
                }
            }
        }
        return result.values().stream().map(ComponentVersions::getStrimziVersion).collect(Collectors.toCollection(ArrayList::new));
    }

    /* test */ public void clearStrimziVersions() {
        this.strimziVersions.clear();
    }

    public String getVersionLabel() {
        return versionLabel;
    }

    public void clearStrimziPendingInstallationVersions() {
        if (this.strimziPendingInstallationVersions.isEmpty()) {
            return;
        }
        log.infof("Clearing pending strimzi versions");
        this.strimziPendingInstallationVersions = new ConcurrentHashMap<>();
        informerManager.resyncManagedKafkaAgent();
    }

    public Collection<String> getStrimziPendingInstallationVersions() {
        return new ArrayList<>(strimziPendingInstallationVersions.keySet());
    }

    /**
     * Notify the strimzi manager of pending versions.
     * @param pendingVersions
     * @return true if the pending state has changed
     */
    public boolean setStrimziPendingInstallationVersions(List<String> pendingVersions) {
        if (!Collections.disjoint(strimziPendingInstallationVersions.keySet(), pendingVersions)) {
            return false;
        }
        log.infof("Notified of pending strimzi versions %s", pendingVersions);
        ConcurrentHashMap<String, ComponentVersions> next = new ConcurrentHashMap<>();
        for (String version : pendingVersions) {
            ComponentVersions existing = strimziVersions.get(version);
            if (existing != null) {
                StrimziVersionStatus strimziVersion = new StrimziVersionStatusBuilder(existing.strimziVersion).withReady(false).build();
                next.put(version, new ComponentVersions(strimziVersion, Map.copyOf(existing.relatedImages)));
            } else {
                next.put(version, EMPTY_STATUS);
            }
        }
        this.strimziPendingInstallationVersions = next;
        informerManager.resyncManagedKafkaAgent();
        return true;
    }
}
