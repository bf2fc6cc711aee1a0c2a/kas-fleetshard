package org.bf2.systemtest.operator;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceFluent.MetadataNested;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.Environment;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Installator of strimzi operator using upstream strimzi
 * TODO remove when fleetshard operator will be able to deploy it itself
 */
public class StrimziOperatorManager {
    private static final String STRIMZI_URL_FORMAT = "https://github.com/strimzi/strimzi-kafka-operator/releases/download/%1$s/strimzi-cluster-operator-%1$s.yaml";

    private static final Logger LOGGER = LogManager.getLogger(StrimziOperatorManager.class);
    public static final String OPERATOR_NS = "strimzi-cluster-operator";

    private final List<Consumer<Void>> clusterWideResourceDeleters = new LinkedList<>();
    protected String operatorNs = OPERATOR_NS;

    public CompletableFuture<Void> installStrimzi(KubeClient kubeClient) throws Exception {
        if (kubeClient.client().apiextensions().v1beta1().customResourceDefinitions().withLabel("app", "strimzi").list().getItems().size() == 0 ||
                kubeClient.client().apps().deployments().inAnyNamespace().list().getItems().stream()
                .noneMatch(deployment -> deployment.getMetadata().getName().contains("strimzi-cluster-operator"))) {
            return doInstall(kubeClient);
        }
        LOGGER.info("Strimzi operator is installed no need to install it");
        return CompletableFuture.completedFuture(null);
    }

    protected CompletableFuture<Void> doInstall(KubeClient kubeClient) throws MalformedURLException, IOException {
        LOGGER.info("Installing Strimzi : {}", operatorNs);

        MetadataNested<NamespaceBuilder> withName = new NamespaceBuilder().withNewMetadata().withName(operatorNs);
        kubeClient.client().namespaces().createOrReplace(namespaceToCreate(withName));
        URL url = new URL(String.format(STRIMZI_URL_FORMAT, Environment.STRIMZI_VERSION));
        List<HasMetadata> opItems = kubeClient.client().load(url.openStream()).get();

        Optional<Deployment> operatorDeployment = opItems.stream().filter(h -> "strimzi-cluster-operator".equals(h.getMetadata().getName()) && h.getKind().equals("Deployment")).map(Deployment.class::cast).findFirst();
        if (operatorDeployment.isPresent()) {
            Deployment deployment = operatorDeployment.get();
            modifyDeployment(deployment);
        }
        opItems.stream().filter(ClusterRoleBinding.class::isInstance).forEach(cwr -> {
            cwr.getMetadata().setName(cwr.getMetadata().getName() + "." + operatorNs);
            clusterWideResourceDeleters.add(unused -> {
                kubeClient.client().rbac().clusterRoleBindings().withName(cwr.getMetadata().getName()).delete();
            });
        });

        // modify namespaces and convert rolebinding to clusterrolebindings
        String crbID = UUID.randomUUID().toString().substring(0, 5);
        opItems.stream().forEach(i -> {
            if (i instanceof Namespaced) {
                i.getMetadata().setNamespace(operatorNs);
            }
            if (i instanceof ClusterRoleBinding) {
                ClusterRoleBinding crb = (ClusterRoleBinding)i;
                crb.getSubjects().forEach(sbj -> sbj.setNamespace(operatorNs));
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
                createClusterRoleBinding(kubeClient, crb);
                clusterWideResourceDeleters.add(unused -> {
                    kubeClient.client().rbac().clusterRoleBindings().withName(crb.getMetadata().getName()).delete();
                });
            }
        });

        opItems.forEach(i -> kubeClient.client().resource(i).inNamespace(operatorNs).createOrReplace());
        LOGGER.info("Done installing Strimzi : {}", operatorNs);
        return TestUtils.asyncWaitFor("Strimzi operator ready", 1_000, 120_000, () ->
                TestUtils.isPodReady(kubeClient.client().pods().inNamespace(operatorNs)
                        .list().getItems().stream().filter(pod ->
                                pod.getMetadata().getName().contains("strimzi-cluster-operator")).findFirst().get()));
    }

    // needed only until the strimzi version is aligned between perf and systemtest
    protected void createClusterRoleBinding(KubeClient kubeClient, ClusterRoleBinding crb) {
        kubeClient.client().rbac().clusterRoleBindings().createOrReplace(crb);
    }

    protected Namespace namespaceToCreate(MetadataNested<NamespaceBuilder> withName) {
        return withName.endMetadata().build();
    }

    protected void modifyDeployment(Deployment deployment) {
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        List<EnvVar> env = new ArrayList<>(container.getEnv() == null ? Collections.emptyList() : container.getEnv());
        EnvVar strimziNS = env.stream().filter(envVar -> envVar.getName().equals("STRIMZI_NAMESPACE")).findFirst().get();
        env.remove(strimziNS);
        env.add(new EnvVarBuilder().withName("STRIMZI_NAMESPACE").withValue("*").build());
        container.setEnv(env);
    }

    public CompletableFuture<Void> uninstallStrimziClusterWideResources(KubeClient kubeClient) {
        if (kubeClient.namespaceExists(operatorNs) && !Environment.SKIP_TEARDOWN) {
            LOGGER.info("Deleting Strimzi : {}", operatorNs);
            kubeClient.client().namespaces().withName(operatorNs).delete();
            clusterWideResourceDeleters.forEach(delete -> delete.accept(null));
            return TestUtils.asyncWaitFor("Delete strimzi", 2_000, 120_000, () ->
                    kubeClient.client().pods().inNamespace(operatorNs).list().getItems().stream().noneMatch(pod ->
                            pod.getMetadata().getName().contains("strimzi-cluster-operator")) &&
                            !kubeClient.namespaceExists(operatorNs));
        } else {
            LOGGER.info("No need to uninstall strimzi operator");
            return CompletableFuture.completedFuture(null);
        }
    }
}
