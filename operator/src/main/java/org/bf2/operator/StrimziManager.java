package org.bf2.operator;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatus;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatusBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class StrimziManager {

    public static final String STRIMZI_PAUSE_RECONCILE_ANNOTATION = "strimzi.io/pause-reconciliation";
    public static final String STRIMZI_PAUSE_REASON_ANNOTATION = "managedkafka.bf2.org/pause-reason";

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    protected InformerManager informerManager;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    // this configuration needs to match with the STRIMZI_CUSTOM_RESOURCE_SELECTOR env var in the Strimzi Deployment(s)
    @ConfigProperty(name = "strimzi.version.label", defaultValue = "managedkafka.bf2.org/strimziVersion")
    protected String versionLabel;

    @PostConstruct
    protected void onStart() {

        // watching for events coming for Strimzi operator Pods, then resync Kafka instances as way to generate events
        // with owner reference to trigger the controller reconcile from the Java operator SDK
        // TODO: move to watch Strimzi operator Deployments when they will be labeled (OLM operator 1.18.0 on OpenShift 4.8)
        resourceInformerFactory.create(Pod.class,
                kubernetesClient.pods().inAnyNamespace().withLabels(Map.of("app.kubernetes.io/part-of", "managed-kafka")),
                new ResourceEventHandler<Pod>() {
                    @Override
                    public void onAdd(Pod pod) {
                        log.debugf("Add event received for Pod %s/%s", pod.getMetadata().getNamespace(), pod.getMetadata().getName());
                        informerManager.resyncKafkas();
                    }

                    @Override
                    public void onUpdate(Pod oldPod, Pod newPod) {
                        log.debugf("Update event received for Pod %s/%s", newPod.getMetadata().getNamespace(), newPod.getMetadata().getName());
                        informerManager.resyncKafkas();
                    }

                    @Override
                    public void onDelete(Pod pod, boolean deletedFinalStateUnknown) {
                        log.debugf("Delete event received for Pod %s/%s", pod.getMetadata().getNamespace(), pod.getMetadata().getName());
                        informerManager.resyncKafkas();
                    }
                });
    }

    /**
     * Handle the Kafka pause/unpause reconciliation mechanism by adding the corresponding annotation on the Kafka custom resource
     *
     * @param managedKafka ManagedKafka instance
     * @param kafkaCluster KafkaCluster instance on which fleetshard reconcile is operating
     * @param annotations Kafka custom resource annotations on which adding/removing the pause
     */
    public void togglePauseReconciliation(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster, Map<String, String> annotations) {
        // a Strimzi version change was asked via the ManagedKafka resource
        if (this.hasStrimziChanged(managedKafka)) {
            log.infof("Strimzi change from %s to %s",
                    this.currentStrimziVersion(managedKafka), managedKafka.getSpec().getVersions().getStrimzi());
            // Kafka cluster is running and ready --> pause reconcile
            if (kafkaCluster.isReady(managedKafka)) {
                pauseReconcile(managedKafka, annotations);
                annotations.put(STRIMZI_PAUSE_REASON_ANNOTATION, ManagedKafkaCondition.Reason.StrimziUpdating.name().toLowerCase());
            // Kafka cluster reconcile is paused because of Strimzi updating --> unpause to restart reconcile
            } else if (kafkaCluster.isReconciliationPaused(managedKafka) && isPauseReasonStrimziUpdate(annotations)) {
                unpauseReconcile(managedKafka, annotations);
            }
        } else {
            // Strimzi version is consistent, Kafka is running and ready --> remove pausing reason for Strimzi updatingf
            if (kafkaCluster.isReady(managedKafka)) {
                if (isPauseReasonStrimziUpdate(annotations)) {
                    annotations.remove(STRIMZI_PAUSE_REASON_ANNOTATION);
                }
            }
        }
    }

    /**
     * Handle the handover of the Kafka custom resource from a Strimzi operator version to another
     * by changing the corresponding selector label value
     *
     * @param managedKafka ManagedKafka instance
     * @param kafkaCluster KafkaCluster instance on which fleetshard reconcile is operating
     * @param labels Kafka custom resource annotations on which adding the selector label value
     */
    public void changeStrimziVersion(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster, Map<String, String> labels) {
        String kafkaStrimziVersion = this.currentStrimziVersion(managedKafka);

        // Kafka cluster reconcile is paused, a Strimzi change was asked via the ManagedKafka resource --> apply version from spec to handover
        if (kafkaCluster.isReconciliationPaused(managedKafka) && this.hasStrimziChanged(managedKafka)) {
            labels.put(this.versionLabel, managedKafka.getSpec().getVersions().getStrimzi());
        // any other state always get Strimzi version from Kafka custom resource label
        } else {
            labels.put(this.versionLabel, kafkaStrimziVersion);
        }
    }

    /**
     * Compare current Strimzi version from the Kafka custom resource with the requested one in the ManagedKafka spec
     * in order to return if a version change happened
     *
     * @param managedKafka ManagedKafka instance
     * @return if a Strimzi version change was requested
     */
    private boolean hasStrimziChanged(ManagedKafka managedKafka) {
        return !this.currentStrimziVersion(managedKafka).equals(managedKafka.getSpec().getVersions().getStrimzi());
    }

    /**
     * Check if the Strimzi version requested in the ManagedKafka resource is installed or not
     *
     * @param managedKafka ManagedKafka instance
     * @return if the Strimzi version specified in the ManagedKafka resource is installed or not
     */
    public boolean isStrimziVersionValid(ManagedKafka managedKafka) {
        List<StrimziVersionStatus> strimziVersions = this.getStrimziVersions();
        return strimziVersions.stream().anyMatch(svs -> managedKafka.getSpec().getVersions().getStrimzi().equals(svs.getVersion()));
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
    private boolean isPauseReasonStrimziUpdate(Map<String, String> annotations) {
        return annotations.containsKey(STRIMZI_PAUSE_REASON_ANNOTATION) &&
                annotations.get(STRIMZI_PAUSE_REASON_ANNOTATION).equals(ManagedKafkaCondition.Reason.StrimziUpdating.name().toLowerCase());
    }

    private Kafka cachedKafka(ManagedKafka managedKafka) {
        return informerManager.getLocalKafka(AbstractKafkaCluster.kafkaClusterNamespace(managedKafka), AbstractKafkaCluster.kafkaClusterName(managedKafka));
    }

    /**
     * Execute the discovery of the Strimzi operators installed on the Kubernetes cluster
     * via a specific label applied on them
     *
     * @return list with Deployment names of Strimzi operator
     */
    public List<StrimziVersionStatus> getStrimziVersions() {

        // checking the ReplicaSet instead of Deployments is a temporary workaround waiting for OpenShift 4.8 (with OLM operator 1.18.0)
        // OLM operator 1.17.0 (on OpenShift 4.7) doesn't support feature to set labels on Deployments from inside the CSV
        // OLM operator 1.18.0 (on OpenShift 4.8) support the above feature
        ReplicaSetList list = this.kubernetesClient.apps()
                .replicaSets()
                .inAnyNamespace()
                .withLabel("app.kubernetes.io/part-of", "managed-kafka")
                .list();

        List<StrimziVersionStatus> strimziVersions = new ArrayList<>();
        log.debug("Strimzi installations");
        if (!list.getItems().isEmpty()) {
            for (ReplicaSet replicaSet : list.getItems()) {

                String deploymentName = replicaSet.getMetadata().getOwnerReferences().get(0).getName();
                Optional<Deployment> optDeployment = this.kubernetesClient.apps()
                        .deployments()
                        .inNamespace(replicaSet.getMetadata().getNamespace())
                        .list().getItems()
                        .stream().filter(d -> d.getMetadata().getName().equals(deploymentName)).findFirst();

                if (optDeployment.isPresent()) {
                    Deployment deployment = optDeployment.get();
                    // check it's ready
                    boolean isReady = deployment.getStatus() != null && deployment.getStatus().getReadyReplicas() != null && deployment.getStatus().getReadyReplicas().equals(deployment.getSpec().getReplicas());
                    strimziVersions.add(new StrimziVersionStatusBuilder()
                            .withVersion(deployment.getMetadata().getName())
                            .withReady(isReady)
                            .build()
                    );
                    log.debugf("\t - %s [%s]", deployment.getMetadata().getName(), isReady);
                }
            }
        }
        return strimziVersions;
    }

    public String getVersionLabel() {
        return versionLabel;
    }
}
