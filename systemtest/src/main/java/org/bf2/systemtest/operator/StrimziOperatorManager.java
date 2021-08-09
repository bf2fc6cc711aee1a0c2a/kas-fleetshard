package org.bf2.systemtest.operator;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.bf2.systemtest.api.github.GithubApiClient;
import org.bf2.systemtest.framework.SystemTestEnvironment;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Installator of strimzi operator using upstream strimzi
 * TODO remove when fleetshard operator will be able to deploy it itself
 */
public class StrimziOperatorManager {
    private static final String STRIMZI_URL_FORMAT = "https://github.com/strimzi/strimzi-kafka-operator/releases/download/%1$s/strimzi-cluster-operator-%1$s.yaml";

    private static final Logger LOGGER = LogManager.getLogger(StrimziOperatorManager.class);
    public static final String DEPLOYMENT_PREFIX = "strimzi-cluster-operator";
    public static final String OPERATOR_NS = DEPLOYMENT_PREFIX;

    private final List<Consumer<Void>> clusterWideResourceDeleters = new LinkedList<>();
    protected String operatorNs = OPERATOR_NS;
    protected String version;

    public StrimziOperatorManager(String version) {
        this.version = version;
    }

    public CompletableFuture<Void> installStrimzi(KubeClient kubeClient) throws Exception {
        if (kubeClient.client().apps().deployments().inAnyNamespace().list().getItems().stream()
                .noneMatch(deployment -> deployment.getMetadata().getName().contains(DEPLOYMENT_PREFIX + ".v" + version)) &&
                kubeClient.client().pods().inAnyNamespace().list().getItems().stream()
                        .noneMatch(pod -> pod.getMetadata().getName().contains(DEPLOYMENT_PREFIX) &&
                                !pod.getMetadata().getLabels().containsKey("installed-by-testsuite"))) {
            return doInstall(kubeClient);
        }
        LOGGER.info("Strimzi operator is installed no need to install it");
        return CompletableFuture.completedFuture(null);
    }

    protected CompletableFuture<Void> doInstall(KubeClient kubeClient) throws IOException {
        LOGGER.info("Installing Strimzi : {} version: {}", operatorNs, version);

        Namespace namespace = new NamespaceBuilder().withNewMetadata().withName(operatorNs).endMetadata().build();
        kubeClient.client().namespaces().createOrReplace(namespace);
        URL url = new URL(String.format(STRIMZI_URL_FORMAT, version));

        // modify namespaces, convert rolebinding to clusterrolebindings, update deployment if needed
        String crbID = UUID.randomUUID().toString().substring(0, 5);
        kubeClient.apply(operatorNs, url.openStream(), i -> {
            if (i instanceof Namespaced) {
                i.getMetadata().setNamespace(operatorNs);
            }
            if (i instanceof ClusterRoleBinding) {
                ClusterRoleBinding crb = (ClusterRoleBinding) i;
                crb.getSubjects().forEach(sbj -> sbj.setNamespace(operatorNs));
                crb.getMetadata().setName(crb.getMetadata().getName() + "." + operatorNs);
                clusterWideResourceDeleters.add(unused -> {
                    kubeClient.client().rbac().clusterRoleBindings().withName(crb.getMetadata().getName()).delete();
                });
            } else if (i instanceof RoleBinding) {
                RoleBinding rb = (RoleBinding) i;
                rb.getSubjects().forEach(sbj -> sbj.setNamespace(operatorNs));

                ClusterRoleBinding crb = new ClusterRoleBindingBuilder()
                        .withNewMetadata()
                        .withName(rb.getMetadata().getName() + "-all-ns-" + crbID)
                        .withAnnotations(rb.getMetadata().getAnnotations())
                        .withLabels(rb.getMetadata().getLabels())
                        .endMetadata()
                        .withRoleRef(rb.getRoleRef())
                        .withSubjects(rb.getSubjects())
                        .build();

                LOGGER.info("Creating {} named {}", crb.getKind(), crb.getMetadata().getName());
                kubeClient.client().rbac().clusterRoleBindings().createOrReplace(crb);
                clusterWideResourceDeleters.add(unused -> {
                    kubeClient.client().rbac().clusterRoleBindings().withName(crb.getMetadata().getName()).delete();
                });
            } else if (i instanceof Deployment && "strimzi-cluster-operator".equals(i.getMetadata().getName())) {
                modifyDeployment((Deployment) i);
            }
            return i;
        });

        LOGGER.info("Done installing Strimzi : {}", operatorNs);
        return TestUtils.asyncWaitFor("Strimzi operator ready", 1_000, FleetShardOperatorManager.INSTALL_TIMEOUT_MS, () ->
                isReady(kubeClient, operatorNs, version));
    }

    public static boolean isReady(KubeClient kubeClient, String namespace, String version) {
        return kubeClient.client().apps().deployments().inNamespace(namespace).list().getItems().stream()
                .anyMatch(dep -> dep.getMetadata().getName().contains(DEPLOYMENT_PREFIX + ".v" + version) && Readiness.isDeploymentReady(dep));
    }

    protected void modifyDeployment(Deployment deployment) {
        String deploymentName = String.format("%s.v%s", DEPLOYMENT_PREFIX, version);
        deployment.getMetadata().setName(deploymentName);
        Map<String, String> labels = deployment.getSpec().getTemplate().getMetadata().getLabels();
        labels.put("app.kubernetes.io/part-of", "managed-kafka");
        labels.put("installed-by-testsuite", "true");
        deployment.getSpec().getTemplate().getMetadata().setLabels(labels);
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        List<EnvVar> env = new ArrayList<>(container.getEnv() == null ? Collections.emptyList() : container.getEnv());
        EnvVar strimziNS = env.stream().filter(envVar -> envVar.getName().equals("STRIMZI_NAMESPACE")).findFirst().get();
        env.remove(strimziNS);
        env.add(new EnvVarBuilder().withName("STRIMZI_NAMESPACE").withValue("*").build());
        // allowing a specific Strimzi operator (just one in systemtest) to select the Kafka resources to work on
        env.add(new EnvVarBuilder().withName("STRIMZI_CUSTOM_RESOURCE_SELECTOR").withValue("managedkafka.bf2.org/strimziVersion=" + deploymentName).build());
        container.setEnv(env);
    }

    public CompletableFuture<Void> uninstallStrimziClusterWideResources(KubeClient kubeClient) {
        if (kubeClient.namespaceExists(operatorNs) && !SystemTestEnvironment.SKIP_TEARDOWN) {
            LOGGER.info("Deleting Strimzi : {}", operatorNs);
            kubeClient.client().namespaces().withName(operatorNs).delete();
            clusterWideResourceDeleters.forEach(delete -> delete.accept(null));
            kubeClient.client().apiextensions().v1().customResourceDefinitions().withLabel("app", "strimzi").delete();
            return TestUtils.asyncWaitFor("Delete strimzi", 2_000, FleetShardOperatorManager.DELETE_TIMEOUT_MS, () ->
                    kubeClient.client().pods().inNamespace(operatorNs).list().getItems().stream().noneMatch(pod ->
                            pod.getMetadata().getName().contains("strimzi-cluster-operator")) &&
                            !kubeClient.namespaceExists(operatorNs));
        } else {
            LOGGER.info("No need to uninstall strimzi operator");
            return CompletableFuture.completedFuture(null);
        }
    }

    public static List<Pod> getStrimziOperatorPods() {
        return KubeClient.getInstance().client().pods().inAnyNamespace()
                .withLabel("name", "strimzi-cluster-operator").list().getItems();
    }

    public static String getPreviousStrimziVersion(String actualVersion) throws InterruptedException, ExecutionException {
        List<String> sortedReleases = Arrays.stream(GithubApiClient.getReleases("strimzi", "strimzi-kafka-operator"))
                .filter(a -> !(a.prerelease || a.draft))
                .sorted((a, b) -> {
                    ComparableVersion aVersion = new ComparableVersion(a.name);
                    ComparableVersion bVersion = new ComparableVersion(b.name);
                    return aVersion.compareTo(bVersion);
                })
                .map(a -> a.name)
                .collect(Collectors.toList());
        return sortedReleases.get(sortedReleases.indexOf(actualVersion) - 1);
    }
}
