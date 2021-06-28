package org.bf2.performance;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1.OperatorGroup;
import io.fabric8.openshift.api.model.operatorhub.v1.OperatorGroupBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.CatalogSource;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.CatalogSourceBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.Subscription;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.SubscriptionCatalogHealth;
import io.fabric8.openshift.client.OpenShiftClient;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.k8s.KubeClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class OlmBasedStrimziOperatorManager {
    private static final String OLM_OPERATOR_GROUP_NAME = "strimzi-opgroup";
    private static final String OLM_SUBSCRIPTION_NAME = "strimzi-subscription";
    private static final String CATALOG_SOURCE_NAME = "strimzi-catalog";
    private static final String CATALOG_SOURCE_IMAGE = "quay.io/mk-ci-cd/kas-strimzi-bundle:index";

    private static final Logger LOGGER = LogManager.getLogger(OlmBasedStrimziOperatorManager.class);
    public static final String OPERATOR_NAME = "strimzi-operator";

    public static CompletableFuture<Void> deployStrimziOperator(KubeClient kubeClient, String namespace) throws Exception {
        if (isOperatorInstalled(kubeClient, namespace)) {
            LOGGER.info("operator is already installed, skipping deployment of operator");
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.info("Installing {}", OPERATOR_NAME);

        if (!kubeClient.namespaceExists(namespace)) {
            kubeClient.client().namespaces().createOrReplace(new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build());
        }

        installCatalogSource(kubeClient, namespace);
        org.bf2.test.TestUtils.waitFor("catalog source ready", 1_000, 120_000, () -> isCatalogSourceInstalled(kubeClient, namespace));
        installOperatorGroup(kubeClient, namespace);
        installSubscription(kubeClient, namespace);
        org.bf2.test.TestUtils.waitFor("subscription source ready", 1_000, 120_000, () -> isSubscriptionInstalled(kubeClient, namespace));

        LOGGER.info("Operator is deployed");
        return org.bf2.test.TestUtils.asyncWaitFor("Operator ready", 1_000, 120_000, () -> isOperatorInstalled(kubeClient, namespace));
    }

    public static CompletableFuture<Void> deleteStrimziOperator(KubeClient kubeClient, String namespace) {
        LOGGER.info("Deleting Strimzi Operator");

        var kafkaCli = kubeClient.client().customResources(Kafka.class);

        kafkaCli.inAnyNamespace().list().getItems()
            .forEach(mk -> kafkaCli.inNamespace(mk.getMetadata().getNamespace())
            .withName(mk.getMetadata().getName())
            .delete());

        LOGGER.info("All the Kafka Instances deleted");

        OpenShiftClient client = kubeClient.client().adapt(OpenShiftClient.class);
        client.operatorHub().subscriptions().inNamespace(namespace).withName(OLM_SUBSCRIPTION_NAME).delete();
        client.operatorHub().operatorGroups().inNamespace(namespace).withName(OLM_OPERATOR_GROUP_NAME).delete();
        client.operatorHub().catalogSources().inNamespace(namespace).withName(CATALOG_SOURCE_NAME).delete();
        kubeClient.client().namespaces().withName(namespace).withGracePeriod(60_000).delete();
        return org.bf2.test.TestUtils.asyncWaitFor("Operator ns deleted", 2_000, 120_000, () -> !kubeClient.namespaceExists(namespace));
    }

    private static void installCatalogSource(KubeClient kubeClient, String namespace) {
        CatalogSource catalogSource = new CatalogSourceBuilder()
                .withApiVersion(namespace)
                .withNewMetadata()
                    .withName(CATALOG_SOURCE_NAME)
                    .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                    .withSourceType("grpc")
                    .withImage(CATALOG_SOURCE_IMAGE)
                    .withDisplayName("KaAS Strimzi Catalog")
                    .withPublisher("Performance Testing")
                .endSpec()
                .build();

        OpenShiftClient client = kubeClient.client().adapt(OpenShiftClient.class);
        client.operatorHub().catalogSources().inNamespace(namespace).createOrReplace(catalogSource);
    }

    private static boolean isCatalogSourceInstalled(KubeClient kubeClient, String namespace) {
        OpenShiftClient client = kubeClient.client().adapt(OpenShiftClient.class);
        CatalogSource cs = client.operatorHub().catalogSources().inNamespace(namespace).withName(CATALOG_SOURCE_NAME).get();
        if (cs != null && cs.getStatus().getConnectionState().getLastObservedState().equals("READY")) {
            return true;
        }
        return false;
    }

    private static void installSubscription(KubeClient kubeClient, String namespace) {
        Subscription subscription = new SubscriptionBuilder()
                .withNewMetadata()
                    .withName(OLM_SUBSCRIPTION_NAME)
                    .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                    .withChannel("stable")
                    .withInstallPlanApproval("Automatic")
                    .withName("kas-strimzi-bundle")
                    .withSource(CATALOG_SOURCE_NAME)
                    .withSourceNamespace(namespace)
                .endSpec()
                .build();

        OpenShiftClient client = kubeClient.client().adapt(OpenShiftClient.class);
        client.operatorHub().subscriptions().inNamespace(namespace).createOrReplace(subscription);
    }

    private static boolean isSubscriptionInstalled(KubeClient kubeClient, String namespace) {
        OpenShiftClient client = kubeClient.client().adapt(OpenShiftClient.class);
        Subscription s = client.operatorHub().subscriptions().inNamespace(namespace).withName(OLM_SUBSCRIPTION_NAME).get();
        if (s != null && s.getStatus() != null && !s.getStatus().getCatalogHealth().isEmpty()) {
            List<SubscriptionCatalogHealth> healths = s.getStatus().getCatalogHealth();
            return !healths.stream()
                .filter(h -> h.getHealthy())
                .map(ref -> ref.getCatalogSourceRef())
                .filter(h -> h.getName().equals(CATALOG_SOURCE_NAME))
                .collect(Collectors.toList())
                .isEmpty();
        }
        return false;
    }

    private static void installOperatorGroup(KubeClient kubeClient, String namespace) {
        OperatorGroup operatorGroup = new OperatorGroupBuilder()
                .withNewMetadata()
                    .withName(OLM_OPERATOR_GROUP_NAME)
                    .withNamespace(namespace)
                .endMetadata()
                .build();

        OpenShiftClient client = kubeClient.client().adapt(OpenShiftClient.class);
        client.operatorHub().operatorGroups().inNamespace(namespace).createOrReplace(operatorGroup);
    }

    public static boolean isOperatorInstalled(KubeClient kubeClient, String namespace) {
        return kubeClient.client().pods().inNamespace(namespace)
                .list().getItems().stream().anyMatch(pod -> pod.getMetadata().getName().contains(OPERATOR_NAME)) &&
                org.bf2.test.TestUtils.isPodReady(kubeClient.client().pods().inNamespace(namespace)
                        .list().getItems().stream().filter(pod ->
                                pod.getMetadata().getName().contains(OPERATOR_NAME)).findFirst().get());
    }
}
