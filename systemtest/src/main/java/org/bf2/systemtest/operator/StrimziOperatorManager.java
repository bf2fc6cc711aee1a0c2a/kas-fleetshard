package org.bf2.systemtest.operator;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Installator of strimzi operator using upstream strimzi
 * TODO remove when fleetshard operator will be able to deploy it itself
 */
public class StrimziOperatorManager {
    private static final Logger LOGGER = LogManager.getLogger(StrimziOperatorManager.class);
    private static final String OPERATOR_NS = "strimzi-cluster-operator";
    private static final List<Consumer<Void>> CLUSTER_WIDE_RESOURCE_DELETERS = new LinkedList<>();

    public static void installStrimzi(KubeClient kubeClient) throws Exception {
        if (kubeClient.client().apiextensions().v1beta1().customResourceDefinitions().withLabel("app", "strimzi").list().getItems().size() == 0 ||
                kubeClient.client().apps().deployments().inAnyNamespace().list().getItems().stream()
                        .noneMatch(deployment -> deployment.getMetadata().getName().contains("strimzi-cluster-operator"))) {
            LOGGER.info("Installing Strimzi : {}", OPERATOR_NS);

            kubeClient.client().namespaces().createOrReplace(new NamespaceBuilder().withNewMetadata().withName(OPERATOR_NS).endMetadata().build());
            URL url = new URL("https://strimzi.io/install/latest?namespace=" + OPERATOR_NS);
            List<HasMetadata> opItems = kubeClient.client().load(url.openStream()).get();

            Optional<Deployment> operatorDeployment = opItems.stream().filter(h -> "strimzi-cluster-operator".equals(h.getMetadata().getName()) && h.getKind().equals("Deployment")).map(Deployment.class::cast).findFirst();
            if (operatorDeployment.isPresent()) {
                Container container = operatorDeployment.get().getSpec().getTemplate().getSpec().getContainers().get(0);
                List<EnvVar> env = new ArrayList<>(container.getEnv() == null ? Collections.emptyList() : container.getEnv());
                EnvVar strimziNS = env.stream().filter(envVar -> envVar.getName().equals("STRIMZI_NAMESPACE")).findFirst().get();
                env.remove(strimziNS);
                env.add(new EnvVarBuilder().withName("STRIMZI_NAMESPACE").withValue("*").build());
                container.setEnv(env);
            }
            opItems.stream().filter(ClusterRoleBinding.class::isInstance).forEach(cwr -> {
                cwr.getMetadata().setName(cwr.getMetadata().getName() + "." + OPERATOR_NS);
                CLUSTER_WIDE_RESOURCE_DELETERS.add(unused -> {
                    kubeClient.client().rbac().clusterRoleBindings().withName(cwr.getMetadata().getName()).delete();
                });
            });
            String crbID = UUID.randomUUID().toString().substring(0, 5);
            opItems.stream().filter(RoleBinding.class::isInstance).forEach(roleBinding -> {
                roleBinding.getMetadata().setNamespace(OPERATOR_NS);
                RoleBinding rb = (RoleBinding) roleBinding;
                rb.getSubjects().forEach(sbj -> sbj.setNamespace(OPERATOR_NS));

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
                CLUSTER_WIDE_RESOURCE_DELETERS.add(unused -> {
                    kubeClient.client().rbac().clusterRoleBindings().withName(crb.getMetadata().getName()).delete();
                });
            });

            opItems.forEach(i -> kubeClient.client().resource(i).inNamespace(OPERATOR_NS).createOrReplace());
            TestUtils.waitFor("Strimzi operator ready", 1_000, 120_000, () ->
                    TestUtils.isPodReady(KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                            .list().getItems().stream().filter(pod ->
                                    pod.getMetadata().getName().contains("strimzi-cluster-operator")).findFirst().get()));
            LOGGER.info("Done installing Strimzi : {}", OPERATOR_NS);
        } else {
            LOGGER.info("Strimzi operator is installed no need to install it");
        }
    }

    public static void uninstallStrimziClusterWideResources(KubeClient kubeClient) {
        if (kubeClient.namespaceExists(OPERATOR_NS)) {
            LOGGER.info("Deleting Strimzi : {}", OPERATOR_NS);
            kubeClient.client().namespaces().withName(OPERATOR_NS).delete();
            CLUSTER_WIDE_RESOURCE_DELETERS.forEach(delete -> delete.accept(null));
            TestUtils.waitFor("Delete strimzi", 2_000, 120_000, () ->
                    kubeClient.client().pods().inNamespace(OPERATOR_NS).list().getItems().stream().noneMatch(pod ->
                            pod.getMetadata().getName().contains("strimzi-cluster-operator")) &&
                            !kubeClient.namespaceExists(OPERATOR_NS));
        } else {
            LOGGER.info("No need to uninstall strimzi operator");
        }
    }
}
