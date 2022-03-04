package org.bf2.operator.managers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
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
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionCondition;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionConditionBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import org.bf2.operator.clients.KafkaResourceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.awaitility.pollinterval.FixedPollInterval.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTestResource(KubernetesServerTestResource.class)
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

    private MixedOperation<PackageManifest, PackageManifestList, Resource<PackageManifest>> packageManifestClient;

    private Watch olmInstallPlanWatch;

    @BeforeEach
    public void beforeEach() {
        this.packageManifestClient = this.openShiftClient.operatorHub().packageManifests();

        // Mimics OLM in so far as it will remove the pending condition from the subscription when the installplan
        // on a manually approved, is approved.
        // approved
        olmInstallPlanWatch = this.openShiftClient.operatorHub().installPlans().inAnyNamespace().watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, InstallPlan resource) {
                if (action == Action.MODIFIED && resource.getSpec().getApproved() && "Manual".equals(resource.getSpec().getApproval())) {
                    OwnerReference ownerReference = resource.getMetadata().getOwnerReferences().get(0);
                    Subscription subscription = openShiftClient.operatorHub().subscriptions().inNamespace(resource.getMetadata().getNamespace()).withName(ownerReference.getName()).get();
                    if (subscription != null && StrimziBundleManager.isInstallPlanApprovalAsManual(subscription)) {
                        List<SubscriptionCondition> c = subscription.getStatus().getConditions().stream()
                                .filter(Predicate.not(StrimziBundleManager::isSubscriptionConditionInstallPlanPendingRequiresApproval))
                                .collect(Collectors.toList());
                        Subscription sub = new SubscriptionBuilder(subscription)
                                .editOrNewStatus()
                                .withConditions(c)
                                .endStatus()
                                .build();
                        openShiftClient.operatorHub().subscriptions().inNamespace(subscription.getMetadata().getNamespace()).replaceStatus(sub);
                    }
                }
            }

            @Override
            public void onClose(WatcherException cause) {
            }
        });
    }

    @AfterEach
    public void afterEach() {
        olmInstallPlanWatch.close();

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
        // check that InstallPlan was approved
        this.awaitInstallPlanApproval(subscription, true);
    }

    @Test
    public void testInstallationWithEmptyStrimzi() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual");
        // check that InstallPlan was not approved due to empty Strimzi versions
        this.awaitInstallPlanApproval(subscription, false);
    }

    @Test
    public void testInstallationWithAutomaticApproval() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Automatic",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");
        // check that InstallPlan was not approved by the bundle manager due to Automatic approval (by OLM)
        this.awaitInstallPlanApproval(subscription, false);
    }

    @Test
    public void testUpdateInstallation() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");
        // check that InstallPlan was approved as first installation with no Kafka CRDs installed
        this.awaitInstallPlanApproval(subscription, true);

        this.createKafkaCRDs();

        subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v2", "strimzi-cluster-operator.v3");
        // check that InstallPlan was approved as update of Strimzi bundle with Kafka CRDs already existing
        this.awaitInstallPlanApproval(subscription, true);
    }

    @Test
    public void testDelayUpdateInstallation()  {
        AtomicBoolean delay = new AtomicBoolean(true);
        this.strimziBundleManager.setApprovalDelayAssessor(l -> delay.get());

        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");

        // check that InstallPlan was approved as first installation with no Kafka CRDs installed
        this.awaitInstallPlanApproval(subscription, true);

        this.createKafkaCRDs();

        subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v2", "strimzi-cluster-operator.v3");

        // check that InstallPlan was approved not yet approved
        this.awaitInstallPlanApproval(subscription, false);

        // the strimzi manager is notified that v2/v3 are pending
        awaitPendingInstallationNotification(Arrays.asList("strimzi-cluster-operator.v2", "strimzi-cluster-operator.v3"));
        delay.set(false);

        // check that InstallPlan was approved as update of Strimzi bundle with Kafka CRDs already existing
        this.awaitInstallPlanApproval(subscription, true);
    }

    @Test
    public void testNotApprovedInstallation() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");
        // check that InstallPlan was approved as first installation with no Kafka CRDs installed
        this.awaitInstallPlanApproval(subscription, true);

        this.createKafkaCRDs();
        this.createOrUpdateKafka("my-kafka-namespace", "my-kafka", "strimzi-cluster-operator.v1");

        subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v2", "strimzi-cluster-operator.v3");
        // check that InstallPlan was not approved due to an orphaned Kafka instance
        this.awaitInstallPlanApproval(subscription, false);
    }

    @Test
    public void testApprovedInstallationAfterKafkaUpdate() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v1", "strimzi-cluster-operator.v2");
        // check that InstallPlan was approved as first installation with no Kafka CRDs installed
        this.awaitInstallPlanApproval(subscription, true);

        this.createKafkaCRDs();
        this.createOrUpdateKafka("my-kafka-namespace", "my-kafka", "strimzi-cluster-operator.v1");

        subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                "strimzi-cluster-operator.v2", "strimzi-cluster-operator.v3");
        // check that InstallPlan was not approved due to an orphaned Kafka instance
        this.awaitInstallPlanApproval(subscription, false);

        this.createOrUpdateKafka("my-kafka-namespace", "my-kafka", "strimzi-cluster-operator.v2");

        // check that InstallPlan was approved after Kafka updated to a newer Strimzi version and not orphan anymore
        this.awaitInstallPlanApproval(subscription, true);
    }

    @Test
    public void testPackageManifestWithoutStatus() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                () -> createPackageManifestWithStatus("kas-strimzi-operator", "kas-strimzi-bundle", null));

        // check that InstallPlan was not approved
        this.awaitInstallPlanApproval(subscription, false);
    }

    @Test
    public void testPackageManifestWithoutChannels() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                () -> createPackageManifestWithStatus("kas-strimzi-operator", "kas-strimzi-bundle", new PackageManifestStatusBuilder().build()));

        // check that InstallPlan was not approved
        this.awaitInstallPlanApproval(subscription, false);
    }

    @Test
    public void testPackageManifestWithoutCurrentCSVDesc() {
        Subscription subscription = this.installOrUpdateBundle("kas-strimzi-operator", "kas-strimzi-bundle", "Manual",
                () -> this.createPackageManifestWithStatus("kas-strimzi-operator", "kas-strimzi-bundle",
                        new PackageManifestStatusBuilder()
                                .withChannels(new PackageChannelBuilder().build())
                                .build()
                ));

        // check that InstallPlan was not approved
        this.awaitInstallPlanApproval(subscription, false);
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

        // check that InstallPlan was not approved
        this.awaitInstallPlanApproval(subscription, false);
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
    private Subscription installOrUpdateBundle(String namespace, String name, String installPlanApproval, String... strimziVersions) {
        return installOrUpdateBundle(namespace, name, installPlanApproval, () -> {
            String jsonStrimziVersions = null;
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                jsonStrimziVersions = objectMapper.writeValueAsString(strimziVersions);
            } catch (JsonProcessingException e) {
                fail(e);
            }

            PackageManifest packageManifest = this.createPackageManifestWithStatus(namespace, name, new PackageManifestStatusBuilder()
                        .withChannels(
                                new PackageChannelBuilder()
                                        .withNewCurrentCSVDesc()
                                        .withAnnotations(Map.of("strimziVersions", jsonStrimziVersions))
                                        .endCurrentCSVDesc()
                                        .build())
                        .build());
            return packageManifest;
        });
    }

    private Subscription installOrUpdateBundle(String namespace, String name, String installPlanApproval, Supplier<PackageManifest> packageManifestSupplier) {
        String installPlanName = "install-" + UUID.randomUUID().toString().substring(0, 4);

        this.packageManifestClient.inNamespace(namespace).createOrReplace(packageManifestSupplier.get());
        Subscription subscription = this.createOrUpdateSubscription(namespace, name + "-sub", name, installPlanName, installPlanApproval);
        this.createOrUpdateInstallPlan(namespace, installPlanName, subscription);
        return subscription;
    }

    private void awaitPendingInstallationNotification(List<String> expected) {
        await(String.format("awaiting pending installation notification : %s", expected))
                .pollInterval(fixed(Duration.ofSeconds(1)))
                .atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(expected, this.strimziManager.getStrimziPendingInstallationVersions()));
    }

    /**
     * Awaits for the InstallPlan to be in the desired approval state
     *
     * @param subscription Subscription which refers to the InstallPlan to check
     * @param desiredApprovalState if need to check on approval or not
     */
    private void awaitInstallPlanApproval(Subscription subscription, boolean desiredApprovalState) {
        await(String.format("awaiting installplan approval state: %s", desiredApprovalState))
                .pollInterval(fixed(Duration.ofSeconds(1)))
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    String name = subscription.getStatus().getInstallPlanRef().getName();
                    InstallPlan installPlan = openShiftClient.operatorHub().installPlans()
                    .inNamespace(subscription.getMetadata().getNamespace())
                    .withName(name)
                    .get();
            assertEquals(desiredApprovalState, installPlan.getSpec().getApproved());
        });
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

    private InstallPlan createOrUpdateInstallPlan(String namespace, String name, Subscription subscription) {
        InstallPlan installPlan = new InstallPlanBuilder()
                .withNewMetadata()
                    .withNamespace(namespace)
                    .withName(name)
                    .withOwnerReferences(new OwnerReferenceBuilder()
                        .withKind(subscription.getKind())
                        .withApiVersion(subscription.getApiVersion())
                        .withName(subscription.getMetadata().getName())
                        .build())
                .endMetadata()
                .withNewSpec()
                    .withApproval("Manual")
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
