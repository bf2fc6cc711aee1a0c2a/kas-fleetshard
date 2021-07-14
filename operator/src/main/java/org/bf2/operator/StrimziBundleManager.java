package org.bf2.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.openshift.api.model.operatorhub.lifecyclemanager.v1.PackageManifest;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.InstallPlan;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.Subscription;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionCondition;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.Startup;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.clients.KafkaResourceClient;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Startup
@ApplicationScoped
// excluding during test profile running on Kubernetes without OLM
@UnlessBuildProfile("test")
public class StrimziBundleManager {

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
    InformerManager informerManager;

    // TODO: this raw custom resource client can be removed once we have Quarkus + SDK extension using the fabric8 5.5.0
    // which supports the package manifests API out of the box
    MixedOperation<PackageManifest, KubernetesResourceList<PackageManifest>, Resource<PackageManifest>> packageManifestClient;

    @PostConstruct
    protected void onStart() {

        this.packageManifestClient = this.createPackageManifestClient();

        this.resourceInformerFactory.create(Subscription.class,
                this.openShiftClient.operatorHub().subscriptions().inAnyNamespace().withLabels(Map.of("app.kubernetes.io/part-of", "managed-kafka")),
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

    private void handleSubscription(Subscription subscription) {
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
                boolean approved = this.approveInstallation(subscription);
                if (approved) {
                    InstallPlan installPlan =
                            this.openShiftClient.operatorHub().installPlans()
                                    .inNamespace(subscription.getStatus().getInstallPlanRef().getNamespace())
                                    .withName(subscription.getStatus().getInstallPlanRef().getName())
                                    .get();

                    installPlan.getSpec().setApproved(true);
                    this.openShiftClient.operatorHub().installPlans().inNamespace(installPlan.getMetadata().getNamespace()).createOrReplace(installPlan);
                }
            } else {
                // not waiting for approval, nothing to do
            }
        } else {
            // it seems never happen
        }
    }

    private boolean approveInstallation(Subscription subscription) {
        PackageManifest packageManifest = this.packageManifestClient.inNamespace(subscription.getMetadata().getNamespace())
                .withName(subscription.getSpec().getName())
                .get();

        List<String> strimziVersions = this.strimziVersionsFromPackageManifest(packageManifest);

        // CRDs are not installed, nothing we can do more ... just approving installation
        if (!this.informerManager.isKafkaCrdsInstalled()) {
            return true;
        } else {
            List<Kafka> kafkas = this.kafkaClient.list();
            int coveredKafkas = 0;
            for (String version : strimziVersions) {
                coveredKafkas +=
                        kafkas.stream()
                                .filter(k -> k.getMetadata().getLabels().containsKey(this.strimziManager.getVersionLabel()) &&
                                        version.equals(k.getMetadata().getLabels().get(this.strimziManager.getVersionLabel())))
                                .count();
            }
            // the Strimzi versions available in the bundle cover all the Kafka instances running
            if (coveredKafkas == kafkas.size()) {
                log.infof("Subscription %s/%s approved", subscription.getMetadata().getNamespace(), subscription.getMetadata().getName());
                return true;
            } else {
                // covered Kafkas should be less, so if this bundle is installed, some Kafkas would be orphaned
                // TODO: checking the spec.installedCSV to report what's missing? and Kafkas that could be orphaned?
                log.infof("Subscription %s/%s not approved. Covered Kafka %s/%s.",
                        subscription.getMetadata().getNamespace(), subscription.getMetadata().getName(), coveredKafkas, kafkas.size());
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
}
