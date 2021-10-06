package org.bf2.systemtest.operator;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.systemtest.framework.SystemTestEnvironment;
import org.bf2.test.TestUtils;
import org.bf2.test.executor.ExecBuilder;
import org.bf2.test.k8s.KubeClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class FleetShardOperatorManager {

    public static final int INSTALL_TIMEOUT_MS = 300_000;
    public static final int DELETE_TIMEOUT_MS = 120_000;

    private static final String CRD_FILE_SUFFIX = "-v1.yml";

    public static final Path CRD_PATH = SystemTestEnvironment.ROOT_PATH.resolve("operator").resolve("target").resolve("kubernetes");

    private static final Logger LOGGER = LogManager.getLogger(FleetShardOperatorManager.class);
    public static final String OPERATOR_NS = "kas-fleetshard";
    public static final String OPERATOR_NAME = "kas-fleetshard-operator";
    public static final String SYNC_NAME = "kas-fleetshard-sync";
    private static List<Path> installedCrds = new ArrayList<>();

    private static void printVar() {
        LOGGER.info("Operator bundle install files: {}", SystemTestEnvironment.YAML_OPERATOR_BUNDLE_PATH);
        LOGGER.info("Sync bundle install files: {}", SystemTestEnvironment.YAML_SYNC_BUNDLE_PATH);
        LOGGER.info("Crds path: {}", CRD_PATH);
    }

    public static CompletableFuture<Void> deployFleetShardOperator(KubeClient kubeClient) throws Exception {
        if (SystemTestEnvironment.SKIP_DEPLOY || isOperatorInstalled(kubeClient)) {
            LOGGER.info("SKIP_DEPLOY is set or operator is already installed, skipping deployment of operator");
            return CompletableFuture.completedFuture(null);
        }
        printVar();
        LOGGER.info("Installing {}", OPERATOR_NAME);

        installedCrds =
                Files.list(CRD_PATH).filter(p -> p.getFileName().toString().endsWith(CRD_FILE_SUFFIX)).collect(Collectors.toList());
        LOGGER.info("Installing CRDs {}", installedCrds);
        installedCrds.forEach(crd -> kubeClient.apply(OPERATOR_NAME, crd));

        if (!kubeClient.namespaceExists(OPERATOR_NS)) {
            kubeClient.client().namespaces().createOrReplace(new NamespaceBuilder().withNewMetadata().withName(OPERATOR_NS).endMetadata().build());
        }
        if (!SystemTestEnvironment.FLEET_SHARD_PULL_SECRET_PATH.isBlank()) {
            deployPullSecrets(kubeClient);
        }
        kubeClient.apply(OPERATOR_NS, SystemTestEnvironment.YAML_OPERATOR_BUNDLE_PATH);
        LOGGER.info("Operator is deployed");
        return TestUtils.asyncWaitFor("Operator ready", 1_000, INSTALL_TIMEOUT_MS, () -> isOperatorInstalled(kubeClient));
    }

    public static CompletableFuture<Void> deployFleetShardSync(KubeClient kubeClient) throws Exception {
        if (SystemTestEnvironment.SKIP_DEPLOY || isSyncInstalled(kubeClient)) {
            LOGGER.info("SKIP_DEPLOY is set or sync is already installed, skipping deployment of sync");
            return CompletableFuture.completedFuture(null);
        }
        LOGGER.info("Installing {}", SYNC_NAME);
        kubeClient.apply(OPERATOR_NS, SystemTestEnvironment.YAML_SYNC_BUNDLE_PATH);
        LOGGER.info("Sync is deployed");
        return TestUtils.asyncWaitFor("Sync ready", 1_000, INSTALL_TIMEOUT_MS, () -> isSyncInstalled(kubeClient));
    }

    static void deployPullSecrets(KubeClient kubeClient) throws Exception {
        LOGGER.info("Deploying secrets for image pull from {}", SystemTestEnvironment.FLEET_SHARD_PULL_SECRET_PATH);
        kubeClient.apply(OPERATOR_NS, Path.of(SystemTestEnvironment.FLEET_SHARD_PULL_SECRET_PATH));
    }

    public static boolean isOperatorInstalled(KubeClient kubeClient) {
        return TestUtils.isReady(kubeClient.client().apps().deployments().inNamespace(OPERATOR_NS).withName(OPERATOR_NAME));
    }

    public static boolean isSyncInstalled(KubeClient kubeClient) {
        return TestUtils.isReady(kubeClient.client().apps().deployments().inNamespace(OPERATOR_NS).withName(SYNC_NAME));
    }

    public static String createEndpoint(KubeClient kubeClient) {
        String externalEndpointName = SYNC_NAME + "-external";
        if (kubeClient.isGenericKubernetes()) {
            if (kubeClient.client().services().inNamespace(OPERATOR_NS).list().getItems().stream().anyMatch(service -> service.getMetadata().getName().equals(externalEndpointName))) {
                kubeClient.client().services().inNamespace(OPERATOR_NS).withName(externalEndpointName).delete();
            }
            kubeClient.cmdClient().namespace(OPERATOR_NS).execInCurrentNamespace("expose", "service", SYNC_NAME, "--type=LoadBalancer", "--name", externalEndpointName);
            return new ExecBuilder()
                    .withCommand("minikube", "service", "--url", externalEndpointName, "-n", OPERATOR_NS)
                    .logToOutput(false)
                    .exec().out().trim();
        } else {
            OpenShiftClient openShiftClient = kubeClient.client().adapt(OpenShiftClient.class);
            if (openShiftClient.routes().inNamespace(OPERATOR_NS).list().getItems().stream().anyMatch(service -> service.getMetadata().getName().equals(externalEndpointName))) {
                openShiftClient.routes().inNamespace(OPERATOR_NS).withName(externalEndpointName).delete();
            }
            kubeClient.cmdClient().namespace(OPERATOR_NS).execInCurrentNamespace("expose", "service", SYNC_NAME, "--name", externalEndpointName);
            Route r = openShiftClient.routes().inNamespace(OPERATOR_NS).withName(externalEndpointName).get();
            return String.format("%s://%s:%d", r.getSpec().getPort().getTargetPort().getStrVal(),
                    r.getSpec().getHost(),
                    r.getSpec().getPort().getTargetPort().getStrVal().equals("http") ? 80 : 443);
        }
    }

    public static CompletableFuture<Void> deleteFleetShard(KubeClient kubeClient) {
        if (!SystemTestEnvironment.SKIP_TEARDOWN) {
            LOGGER.info("Deleting managedkafkas and kas-fleetshard");
            var mkCli = kubeClient.client().resources(ManagedKafka.class);
            mkCli.inAnyNamespace().list().getItems().forEach(mk -> mkCli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).delete());
            installedCrds.forEach(crd -> {
                String fileName = crd.getFileName().toString();
                String crdName = fileName.substring(0, fileName.length() - CRD_FILE_SUFFIX.length());
                LOGGER.info("Delete CRD {}", crdName);
                kubeClient.client().apiextensions().v1().customResourceDefinitions().withName(crdName).delete();
            });
            LOGGER.info("Crds deleted");
            kubeClient.client().namespaces().withName(OPERATOR_NS).withGracePeriod(60_000).delete();

            var rbac = kubeClient.client().rbac();
            List<String> clusterRoles = new ArrayList<>();

            rbac.clusterRoleBindings().list().getItems().forEach(roleBinding -> {
                if (roleBinding.getSubjects().stream().anyMatch(sub -> OPERATOR_NS.equals(sub.getNamespace()))) {
                    clusterRoles.add(roleBinding.getRoleRef().getName());
                    LOGGER.info("Delete ClusterRoleBinding: {}", roleBinding.getMetadata().getName());
                    rbac.clusterRoleBindings().withName(roleBinding.getMetadata().getName()).delete();
                }
            });

            clusterRoles.stream().map(rbac.clusterRoles()::withName).forEach(role -> {
                LOGGER.info("Delete ClusterRole: {}", role.get().getMetadata().getName());
                role.delete();
            });

            return TestUtils.asyncWaitFor("Operator ns deleted", 2_000, DELETE_TIMEOUT_MS, () -> !kubeClient.namespaceExists(OPERATOR_NS));
        } else {
            LOGGER.info("SKIP_TEARDOWN is set to true.");
            return CompletableFuture.completedFuture(null);
        }
    }
}
