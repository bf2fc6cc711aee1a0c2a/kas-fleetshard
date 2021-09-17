package org.bf2.operator.managers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageChannelBuilder;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifest;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifestBuilder;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifestList;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifestStatus;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifestStatusBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.InstallPlan;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.InstallPlanBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.Subscription;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionConditionBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import org.bf2.operator.clients.KafkaResourceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@WithKubernetesTestServer
@QuarkusTest
public class StrimziBundleManagerTest {

    @Inject
    StrimziBundleManager strimziBundleManager;

    @Inject
    OpenShiftClient openShiftClient;

    @Inject
    KafkaResourceClient kafkaClient;

    @Inject
    StrimziManager strimziManager;

    MixedOperation<PackageManifest, PackageManifestList, Resource<PackageManifest>> packageManifestClient;

    @BeforeEach
    public void beforeEach() {
        this.packageManifestClient = this.openShiftClient.operatorHub().packageManifests();

        // cleaning OpenShift cluster
        this.openShiftClient.operatorHub().subscriptions().inAnyNamespace().delete();
        this.openShiftClient.operatorHub().installPlans().inAnyNamespace().delete();
        this.openShiftClient.apiextensions().v1().customResourceDefinitions().delete();
        this.packageManifestClient.inAnyNamespace().delete();
        this.kafkaClient.delete();
    }

    @Test
    public void testFirstInstallation() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");
        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was approved
        this.checkInstallPlan(subscription, true);
    }

    @Test
    public void testInstallationWithEmptyStrimzi() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual", null);
        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was not approved due to empty Strimzi versions
        this.checkInstallPlan(subscription, false);
    }

    @Test
    public void testInstallationWithAutomaticApproval() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Automatic",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");
        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was not approved by the bundle manager due to Automatic approval (by OLM)
        this.checkInstallPlan(subscription, false);
    }

    @Test
    public void testUpdateInstallation() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");
        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was approved as first installation with no Kafka CRDs installed
        this.checkInstallPlan(subscription, true);

        this.createKafkaCRDs();

        subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v2", "strimzi-cluster-operator.v3");
        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was approved as update of Strimzi bundle with Kafka CRDs already existing
        this.checkInstallPlan(subscription, true);
    }

    @Test
    public void testNotApprovedInstallation() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");
        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was approved as first installation with no Kafka CRDs installed
        this.checkInstallPlan(subscription, true);

        this.createKafkaCRDs();
        this.createOrUpdateKafka("my-kafka-namespace", "my-kafka", "strimzi-cluster-operator.v1");

        subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v2", "strimzi-cluster-operator.v3");
        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was not approved due to an orphaned Kafka instance
        this.checkInstallPlan(subscription, false);
    }

    @Test
    public void testApprovedInstallationAfterKafkaUpdate() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");
        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was approved as first installation with no Kafka CRDs installed
        this.checkInstallPlan(subscription, true);

        this.createKafkaCRDs();
        this.createOrUpdateKafka("my-kafka-namespace", "my-kafka", "strimzi-cluster-operator.v1");

        subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v2", "strimzi-cluster-operator.v3");
        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was not approved due to an orphaned Kafka instance
        this.checkInstallPlan(subscription, false);

        this.createOrUpdateKafka("my-kafka-namespace", "my-kafka", "strimzi-cluster-operator.v2");

        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was approved after Kafka updated to a newer Strimzi version and not orphan anymore
        this.checkInstallPlan(subscription, true);
    }

    @Test
    public void testPackageManifestWithoutStatus() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");

        // overwrite the PackaheManifest with a "bad" one, completely missing the status
        PackageManifest packageManifest = this.createPackageManifestWithStatus("kas-strimzi-operator", "kas-strimzi-bundle", null);
        this.packageManifestClient.inNamespace("kas-strimzi-operator").createOrReplace(packageManifest);

        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was not approved
        this.checkInstallPlan(subscription, false);
    }

    @Test
    public void testPackageManifestWithoutChannels() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");

        // overwrite the PackaheManifest with a "bad" one, completely missing the channel with the CSV description
        PackageManifest packageManifest = this.createPackageManifestWithStatus("kas-strimzi-operator", "kas-strimzi-bundle", new PackageManifestStatusBuilder().build());
        this.packageManifestClient.inNamespace("kas-strimzi-operator").createOrReplace(packageManifest);

        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was not approved
        this.checkInstallPlan(subscription, false);
    }

    @Test
    public void testPackageManifestWithoutCurrentCSVDesc() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");

        // overwrite the PackaheManifest with a "bad" one, completely missing the CSV description
        PackageManifest packageManifest = this.createPackageManifestWithStatus("kas-strimzi-operator", "kas-strimzi-bundle",
                new PackageManifestStatusBuilder()
                        .withChannels(new PackageChannelBuilder().build())
                        .build()
        );
        this.packageManifestClient.inNamespace("kas-strimzi-operator").createOrReplace(packageManifest);

        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was not approved
        this.checkInstallPlan(subscription, false);
    }

    @Test
    public void testPackageManifestWithoutCurrentCSVDescAnnotations() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");

        // overwrite the PackaheManifest with a "bad" one, missing the annotations with Strimzi versions in the CSV description
        PackageManifest packageManifest = this.createPackageManifestWithStatus("kas-strimzi-operator", "kas-strimzi-bundle",
                new PackageManifestStatusBuilder()
                        .withChannels(
                                new PackageChannelBuilder()
                                        .withNewCurrentCSVDesc()
                                        .endCurrentCSVDesc()
                                        .build())
                        .build()
        );
        this.packageManifestClient.inNamespace("kas-strimzi-operator").createOrReplace(packageManifest);

        this.strimziBundleManager.handleSubscription(subscription);
        // check that InstallPlan was not approved
        this.checkInstallPlan(subscription, false);
    }

    /**
     * Install or update a Strimzi bundle, creating/updating the corresponding resources
     * like Subscription, InstallPlan and PackageManifest
     *
     * @param namespace namespace where installing the bundle
     * @param name name of the bundle
     * @param installPlanApproval approval for the install plan (Manual or Automatic)
     * @param strimziVersions Strimzi versions provided by the bundle
     * @return Subscription created by the installation/update
     */
    private Subscription installOrUpdateBundle(String namespace, String name, String installPlanApproval, String ... strimziVersions) {
        String installPlan = "install-" + UUID.randomUUID().toString().substring(0, 4);

        String jsonStrimziVersions = null;
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            jsonStrimziVersions = objectMapper.writeValueAsString(strimziVersions);
        } catch (JsonProcessingException e) {
            fail(e);
        }

        Subscription subscription = this.createOrUpdateSubscription(namespace, name + "-sub", name, installPlan, installPlanApproval);
        this.createOrUpdateInstallPlan(namespace, installPlan);
        this.createOrUpdatePackageManifest(namespace, name, jsonStrimziVersions);
        return subscription;
    }

    /**
     * Check that the InstallPlan is in the provided state (approved or not)
     *
     * @param subscription Subscription which refers to the InstallPlan to check
     * @param approved if need to check on approval or not
     */
    private void checkInstallPlan(Subscription subscription, boolean approved) {
        InstallPlan installPlan = this.openShiftClient.operatorHub().installPlans()
                .inNamespace(subscription.getMetadata().getNamespace())
                .withName(subscription.getStatus().getInstallPlanRef().getName())
                .get();
        assertEquals(approved, installPlan.getSpec().getApproved());
    }

    private PackageManifest createPackageManifestWithStatus(String namespace, String name, PackageManifestStatus status) {
        PackageManifestBuilder packageManifestBuilder = new PackageManifestBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                .endMetadata();
        if (status != null) {
            packageManifestBuilder.withStatus(status);
        }
        return packageManifestBuilder.build();
    }

    private PackageManifest createOrUpdatePackageManifest(String namespace, String name, String jsonStrimziVersions) {
        PackageManifest packageManifest = this.createPackageManifestWithStatus(namespace, name,
                new PackageManifestStatusBuilder()
                        .withChannels(
                                new PackageChannelBuilder()
                                        .withNewCurrentCSVDesc()
                                            .withAnnotations(Map.of("strimziVersions", jsonStrimziVersions))
                                        .endCurrentCSVDesc()
                                        .build())
                        .build()
        );

        this.packageManifestClient.inNamespace(namespace).createOrReplace(packageManifest);
        return packageManifest;
    }

    private InstallPlan createOrUpdateInstallPlan(String namespace, String name) {
        InstallPlan installPlan = new InstallPlanBuilder()
                .withNewMetadata()
                    .withNamespace(namespace)
                    .withName(name)
                .endMetadata()
                .withNewSpec()
                    .withApproved(false)
                .endSpec()
                .build();

        this.openShiftClient.operatorHub().installPlans().inNamespace(namespace).createOrReplace(installPlan);
        return installPlan;
    }

    private Subscription createOrUpdateSubscription(String namespace, String name, String bundleName, String installPlan, String installPlanApproval) {
        Subscription subscription = new SubscriptionBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(Map.of("app.kubernetes.io/part-of", "managed-kafka", "app.kubernetes.io/component", "strimzi-bundle"))
                .endMetadata()
                .withNewSpec()
                    .withName(bundleName)
                    .withInstallPlanApproval(installPlanApproval)
                .endSpec()
                .withNewStatus()
                    .withConditions(new SubscriptionConditionBuilder().withType("InstallPlanPending").withReason("RequiresApproval").build())
                    .withInstallPlanRef(new ObjectReferenceBuilder().withNamespace(namespace).withName(installPlan).build())
                .endStatus()
                .build();

        this.openShiftClient.operatorHub().subscriptions().inNamespace(namespace).createOrReplace(subscription);
        return subscription;
    }

    private CustomResourceDefinition createKafkaCRDs() {
        CustomResourceDefinition crd = new CustomResourceDefinitionBuilder(Crds.kafka())
                .editMetadata()
                    .withName("kafkas.kafka.strimzi.io")
                    .withLabels(Map.of("app", "strimzi"))
                .endMetadata()
                .build();

        this.openShiftClient.apiextensions().v1().customResourceDefinitions().create(crd);
        return crd;
    }

    private Kafka createOrUpdateKafka(String namespace, String name, String strimziVersion) {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(Map.of(this.strimziManager.getVersionLabel(), strimziVersion))
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();

        this.kafkaClient.createOrUpdate(kafka);
        return kafka;
    }

}
