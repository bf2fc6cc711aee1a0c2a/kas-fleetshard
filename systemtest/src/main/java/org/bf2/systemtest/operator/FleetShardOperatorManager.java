package org.bf2.systemtest.operator;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.test.Environment;
import org.bf2.test.TestUtils;
import org.bf2.test.executor.ExecBuilder;
import org.bf2.test.k8s.KubeClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class FleetShardOperatorManager {
    private static final String CRD_FILE_SUFFIX = "-v1.yml";
    private static final String YAML_OPERATOR_BUNDLE_PATH_ENV = "YAML_OPERATOR_BUNDLE_PATH";
    private static final String YAML_SYNC_BUNDLE_PATH_ENV = "YAML_SYNC_BUNDLE_PATH";
    private static final String FLEET_SHARD_PULL_SECRET_PATH_ENV = "FLEET_SHARD_PULL_SECRET_PATH";

    public static final Path ROOT_PATH = Objects.requireNonNullElseGet(Paths.get(System.getProperty("user.dir")).getParent(), () -> Paths.get(System.getProperty("maven.multiModuleProjectDirectory")));
    public static final Path YAML_OPERATOR_BUNDLE_PATH = Environment.getOrDefault(YAML_OPERATOR_BUNDLE_PATH_ENV, Paths::get, Paths.get(ROOT_PATH.toString(), "operator", "target", "kubernetes", "kubernetes.yml"));
    public static final Path YAML_SYNC_BUNDLE_PATH = Environment.getOrDefault(YAML_SYNC_BUNDLE_PATH_ENV, Paths::get, Paths.get(ROOT_PATH.toString(), "sync", "target", "kubernetes", "kubernetes.yml"));
    public static final Path CRD_PATH = ROOT_PATH.resolve("api").resolve("target").resolve("classes").resolve("META-INF").resolve("fabric8");

    public static final String FLEET_SHARD_PULL_SECRET_PATH = Environment.getOrDefault(FLEET_SHARD_PULL_SECRET_PATH_ENV, "");

    private static final Logger LOGGER = LogManager.getLogger(FleetShardOperatorManager.class);
    public static final String OPERATOR_NS = "kas-fleetshard";
    public static final String OPERATOR_NAME = "kas-fleetshard-operator";
    public static final String SYNC_NAME = "kas-fleetshard-sync";
    private static Path[] installedCrds = new Path[0];

    private static void printVar() {
        LOGGER.info("Operator bundle install files: {}", YAML_OPERATOR_BUNDLE_PATH);
        LOGGER.info("Sync bundle install files: {}", YAML_SYNC_BUNDLE_PATH);
        LOGGER.info("Crds path: {}", CRD_PATH);
    }

    public static CompletableFuture<Void> deployFleetShardOperator(KubeClient kubeClient) throws Exception {
        if (Environment.SKIP_DEPLOY || isOperatorInstalled()) {
            LOGGER.info("SKIP_DEPLOY is set or operator is already installed, skipping deployment of operator");
            return CompletableFuture.completedFuture(null);
        }
        printVar();
        LOGGER.info("Installing {}", OPERATOR_NAME);

        installedCrds =
                Files.list(CRD_PATH).filter(p -> p.getFileName().toString().endsWith(CRD_FILE_SUFFIX)).toArray(Path[]::new);
        LOGGER.info("Installing CRDs {}", Arrays.toString(installedCrds));
        kubeClient.apply(OPERATOR_NS, installedCrds);

        if (!kubeClient.namespaceExists(OPERATOR_NS)) {
            kubeClient.client().namespaces().createOrReplace(new NamespaceBuilder().withNewMetadata().withName(OPERATOR_NS).endMetadata().build());
        }
        if (!FLEET_SHARD_PULL_SECRET_PATH.isBlank()) {
            deployPullSecrets(kubeClient);
        }
        kubeClient.apply(OPERATOR_NS, YAML_OPERATOR_BUNDLE_PATH);
        LOGGER.info("Operator is deployed");
        return TestUtils.asyncWaitFor("Operator ready", 1_000, 120_000, FleetShardOperatorManager::isOperatorInstalled);
    }

    public static CompletableFuture<Void> deployFleetShardSync(KubeClient kubeClient) throws Exception {
        if (Environment.SKIP_DEPLOY || isSyncInstalled()) {
            LOGGER.info("SKIP_DEPLOY is set or sync is already installed, skipping deployment of sync");
            return CompletableFuture.completedFuture(null);
        }
        LOGGER.info("Installing {}", SYNC_NAME);
        kubeClient.apply(OPERATOR_NS, YAML_SYNC_BUNDLE_PATH);
        LOGGER.info("Sync is deployed");
        return TestUtils.asyncWaitFor("Sync ready", 1_000, 120_000, FleetShardOperatorManager::isSyncInstalled);
    }

    static void deployPullSecrets(KubeClient kubeClient) throws Exception {
        LOGGER.info("Deploying secrets for image pull from {}", FLEET_SHARD_PULL_SECRET_PATH);
        kubeClient.apply(OPERATOR_NS, Path.of(FLEET_SHARD_PULL_SECRET_PATH));
    }

    public static boolean isOperatorInstalled() {
        return KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                .list().getItems().stream().anyMatch(pod -> pod.getMetadata().getName().contains(OPERATOR_NAME)) &&
                TestUtils.isPodReady(KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                        .list().getItems().stream().filter(pod ->
                                pod.getMetadata().getName().contains(OPERATOR_NAME)).findFirst().get());
    }

    public static boolean isSyncInstalled() {
        return KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                .list().getItems().stream().anyMatch(pod -> pod.getMetadata().getName().contains(SYNC_NAME)) &&
                TestUtils.isPodReady(KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                        .list().getItems().stream().filter(pod ->
                                pod.getMetadata().getName().contains(SYNC_NAME)).findFirst().get());
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
        if (!Environment.SKIP_TEARDOWN) {
            LOGGER.info("Deleting managedkafkas and kas-fleetshard");
            var mkCli = kubeClient.client().customResources(ManagedKafka.class);
            mkCli.inAnyNamespace().list().getItems().forEach(mk -> mkCli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).delete());
            Arrays.asList(installedCrds).forEach(crd -> {
                String fileName = crd.getFileName().toString();
                String crdName = fileName.substring(0, fileName.length() - CRD_FILE_SUFFIX.length());
                LOGGER.info("Delete CRD {}", crdName);
                kubeClient.client().apiextensions().v1().customResourceDefinitions().withName(crdName).delete();
            });
            LOGGER.info("Crds deleted");
            kubeClient.client().namespaces().withName(OPERATOR_NS).withGracePeriod(60_000).delete();
            return TestUtils.asyncWaitFor("Operator ns deleted", 2_000, 120_000, () -> !kubeClient.namespaceExists(OPERATOR_NS));
        } else {
            LOGGER.info("SKIP_TEARDOWN is set to true.");
            return CompletableFuture.completedFuture(null);
        }
    }
}
