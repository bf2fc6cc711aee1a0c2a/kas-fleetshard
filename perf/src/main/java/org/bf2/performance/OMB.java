package org.bf2.performance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSetStatus;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.openmessaging.benchmark.TestResult;
import io.openmessaging.benchmark.WorkloadGenerator;
import io.openmessaging.benchmark.worker.DistributedWorkersEnsemble;
import io.openmessaging.benchmark.worker.LocalWorker;
import io.openmessaging.benchmark.worker.Worker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.performance.framework.TestMetadataCapture;
import org.bf2.systemtest.framework.SecurityUtils.TlsConfig;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Handles installation and running of OpenMessagingBenchmark
 */
public class OMB {
    private static final Logger LOGGER = LogManager.getLogger(OMB.class);
    private static final int N_THREADS = 4;

    private final KubeClusterResource ombCluster;
    private List<String> workerNames = new CopyOnWriteArrayList<>();
    private Quantity workerContainerMemory = Quantity.parse("4Gi");
    private Quantity workerCpu = Quantity.parse("750m");
    private Set<EnvVar> envVars = new HashSet<>();
    boolean useSingleNode = false;

    public OMB(KubeClusterResource ombCluster) throws IOException {
        this.ombCluster = ombCluster;
        TestMetadataCapture.getInstance().storeClientsOpenshiftEnv(ombCluster);
    }

    public void setWorkerContainerMemory(Quantity workerContainerMemory) {
        this.workerContainerMemory = workerContainerMemory;
    }

    public Quantity getWorkerCpu() {
        return workerCpu;
    }

    public void setWorkerCpu(Quantity workerCpu) {
        this.workerCpu = workerCpu;
    }

    public void addToEnv(EnvVar envVar) {
        envVars.add(envVar);
    }

    public List<String> getWorkerNames() {
        return workerNames;
    }

    public Quantity getWorkerContainerMemory() {
        return workerContainerMemory;
    }

    public Set<EnvVar> getEnvVars() {
        return envVars;
    }

    /**
     * Install build config, image stream and trust cert. Trigger the initial build.
     */
    public void install(TlsConfig tlsConfig) throws IOException {
        install(tlsConfig.getTrustStoreBase64());
    }

    public void install(String base64EncodedTrustStore) throws IOException {
        LOGGER.info("Installing OMB in namespace {}", Constants.OMB_NAMESPACE);

        pullAndHoldWorkerImageToAllNodesUsingDaemonSet();

        Map<String, String> nsAnnotations = new HashMap<>();
        nsAnnotations.put(Constants.ORG_BF2_PERFORMANCE_CHECKRESTARTEDCONTAINERS, "true");
        if (PerformanceEnvironment.OMB_COLLECT_LOG) {
            nsAnnotations.put(Constants.ORG_BF2_KAFKA_PERFORMANCE_COLLECTPODLOG, "true");
        }
        ombCluster.createNamespace(Constants.OMB_NAMESPACE, nsAnnotations, Map.of());
        ombCluster.kubeClient().client().secrets().inNamespace(Constants.OMB_NAMESPACE).create(new SecretBuilder()
                .editOrNewMetadata()
                .withName("ext-listener-crt")
                .withNamespace(Constants.OMB_NAMESPACE)
                .endMetadata()
                .addToData("listener.jks", base64EncodedTrustStore)
                .build());

        LOGGER.info("Done installing OMB in namespace {}", Constants.OMB_NAMESPACE);
    }

    /**
     * Deploy workers to run producers and consumers.
     *
     * @param workers The number of workers to deploy.
     * @return List of worker hostnames.
     */
    public List<String> deployWorkers(int workers) throws Exception {
        LOGGER.info("Deploying {} workers, container memory: {}, cpu: {}", workers, workerContainerMemory, workerCpu);
        // we are now on java 11 which defaults to https://www.eclipse.org/openj9/docs/xxusecontainersupport/ and -XX:+PreferContainerQuotaForCPUCount
        String jvmOpts = String.format("-XX:+ExitOnOutOfMemoryError");
        CompletableFuture<?>[] futures = new CompletableFuture[workers];
        List<Node> nodes = ombCluster.getWorkerNodes();
        ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);
        try {
            for (int i = 0; i < workers; i++) {
                String name = String.format("worker-%d", i);
                final int nodeIdx = i % nodes.size();
                futures[i] = CompletableFuture.runAsync(() -> {
                    workerNames.add(name);
                    createWorker(jvmOpts, name, this.useSingleNode ? nodes.get(0) : nodes.get(nodeIdx));
                }, executorService);
            }
            CompletableFuture.allOf(futures).join();
        } finally {
            executorService.shutdown();
        }
        LOGGER.info("Collecting hosts");

        TreeMap<Integer, String> sortedHostnames = new TreeMap<>();
        ombCluster.kubeClient().client().adapt(OpenShiftClient.class).routes().inNamespace(Constants.OMB_NAMESPACE).withLabel("app", "worker").list().getItems().forEach(r -> {
            String host = r.getSpec().getHost();
            if (host == null || host.isEmpty()) {
                throw new IllegalStateException("Host node not defined");
            }
            sortedHostnames.put(workerNames.indexOf(r.getMetadata().getLabels().get("app.kubernetes.io/name")), String.format("http://%s", host));
        });
        List<String> hostnames = new ArrayList<>(sortedHostnames.values());

        LOGGER.info("Waiting for worker pods to run");
        // Wait until workers are running
        List<Pod> pods = ombCluster.kubeClient().client().pods().inNamespace(Constants.OMB_NAMESPACE).withLabel("app", "worker").list().getItems();
        while (pods.size() != workers) {
            pods = ombCluster.kubeClient().client().pods().inNamespace(Constants.OMB_NAMESPACE).withLabel("app", "worker").list().getItems();
            LOGGER.info("Found {} pods, expecting {}", pods.size(), workers);
            Thread.sleep(5000);
        }
        CompletableFuture<?>[] ready = new CompletableFuture<?>[pods.size()];
        for (int i = 0; i < pods.size(); i++) {
            Pod pod = pods.get(i);
            ready[i] = TestUtils.asyncWaitFor("pod ready", 1_000, 600_000, () -> ombCluster.kubeClient().client().pods().inNamespace(Constants.OMB_NAMESPACE).withName(pod.getMetadata().getName()).isReady());
        }
        CompletableFuture.allOf(ready).get();

        HttpClient client = HttpClient.newHttpClient();
        List<URI> notReady = hostnames.stream().map(u -> u + "/counters-stats").map(URI::create).collect(Collectors.toList());
        do {
            Iterator<URI> itr = notReady.iterator();
            LOGGER.info("Awaiting {} OMB endpoint(s) to become ready.", notReady.size());
            while (itr.hasNext()) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(itr.next())
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    itr.remove();
                }
            }
            Thread.sleep(1000);
        } while (!notReady.isEmpty());

        LOGGER.info("Deployed {} workers: {}", workers, hostnames);
        return hostnames;
    }

    private void createWorker(String jvmOpts, String name, Node node) {
        KubeClient kubeClient = ombCluster.kubeClient();
        DeploymentBuilder deploymentBuilder = new DeploymentBuilder()
                .editOrNewMetadata()
                .withName(name)
                .withNamespace(Constants.OMB_NAMESPACE)
                .addToLabels("app", "worker")
                .endMetadata()
                .editOrNewSpec()
                .withReplicas(1)
                .editOrNewSelector()
                .addToMatchLabels("worker", name)
                .endSelector()
                .editOrNewTemplate()
                .editOrNewMetadata()
                .addToLabels("worker", name)
                .addToLabels("app", "worker")
                .endMetadata()
                .editOrNewSpec()
                .addNewContainer()
                .withName("worker")
                .withImage(Constants.OMB_WORKER_IMAGE)
                .withResources(new ResourceRequirementsBuilder()
                        .withLimits(getResourceLimits())
                        .withRequests(getResourceLimits())
                        .build())
                .addToCommand("sh", "-c")
                .addToEnv(new EnvVar("_JAVA_OPTIONS", jvmOpts, null))
                .addToEnv(envVars.toArray(new EnvVar[0]))
                .addToArgs("cd /tmp/src; ./bin/benchmark-worker")
                .addToPorts(new ContainerPortBuilder()
                                .withContainerPort(8080)
                                .build(),
                        new ContainerPortBuilder()
                                .withContainerPort(8081)
                                .build())
                .withLivenessProbe(new ProbeBuilder()
                        .withInitialDelaySeconds(10)
                        .withHttpGet(new HTTPGetActionBuilder()
                                .withPort(new IntOrString(8080))
                                .withPath("counters-stats")
                                .build())
                        .build())
                .addNewVolumeMount()
                .withName("ca")
                .withMountPath("/cert")
                .withReadOnly(true)
                .endVolumeMount()
                .endContainer()
                .withTerminationGracePeriodSeconds(15L)
                .addNewVolume()
                .withName("ca")
                .editOrNewSecret()
                .withSecretName("ext-listener-crt")
                .endSecret()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec();

        if (node != null) {
            deploymentBuilder.editSpec()
            .editTemplate()
                .editSpec()
                    .withNodeSelector(Collections.singletonMap("kubernetes.io/hostname", node.getMetadata().getLabels().get("kubernetes.io/hostname")))
                .endSpec()
            .endTemplate()
            .endSpec();
        }

        kubeClient.client().apps().deployments().inNamespace(Constants.OMB_NAMESPACE).createOrReplace(deploymentBuilder.build());
        kubeClient.client().services().inNamespace(Constants.OMB_NAMESPACE).createOrReplace(new ServiceBuilder()
                .editOrNewMetadata()
                .withName(name)
                .withNamespace(Constants.OMB_NAMESPACE)
                .addToLabels("app", "worker")
                .endMetadata()
                .editOrNewSpec()
                .addToSelector("worker", name)
                .addNewPort()
                .withPort(80)
                .withTargetPort(new IntOrString(8080))
                .withProtocol("TCP")
                .endPort()
                .endSpec()
                .build());

        kubeClient.client().adapt(OpenShiftClient.class).routes().inNamespace(Constants.OMB_NAMESPACE).createOrReplace(new RouteBuilder()
                .editOrNewMetadata()
                .withName(name)
                .withNamespace(Constants.OMB_NAMESPACE)
                .withAnnotations(Map.of("haproxy.router.openshift.io/timeout", "360s"))
                .addToLabels("app", "worker")
                .addToLabels("app.kubernetes.io/name", name)
                .endMetadata()
                .editOrNewSpec()
                .editOrNewTo()
                .withKind("Service")
                .withName(name)
                .endTo()
                .endSpec()
                .build());
    }

    private Map<String, Quantity> getResourceLimits() {
        Map<String, Quantity> limits = new HashMap<>();
        if (workerContainerMemory != null) {
            limits.put("memory", workerContainerMemory);
        }
        if (workerCpu != null) {
            limits.put("cpu", workerCpu);
        }
        return limits;
    }


    private static final ObjectMapper MAPPER =
        new ObjectMapper(new YAMLFactory().configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, true)
                         .configure(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE, true))
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

    private static final ObjectWriter WRITER = new ObjectMapper().writerWithDefaultPrettyPrinter();

    public OMBWorkloadResult runWorkload(File testDir, OMBDriver driver, List<String> workers, OMBWorkload workload) throws Exception {
        File driverFile = new File(testDir, "driver.yaml");
        File resultFile = new File(testDir, String.format("result_%s.json", workload.getName().replaceAll(" ", "_")));

        Files.writeString(driverFile.toPath(), MAPPER.writeValueAsString(driver));
        LOGGER.info("Wrote driver to {}", driverFile.getAbsolutePath());

        workload.validate();

        boolean complete = false;

        try (
             Worker worker = workers.isEmpty()?new LocalWorker():new DistributedWorkersEnsemble(workers, false);
             WorkloadGenerator generator = new WorkloadGenerator(driver.name, workload, worker);
        ) {
            LOGGER.info("--------------- WORKLOAD: {} --- DRIVER: {} ---------------", workload.name, driver.name);

            worker.initializeDriver(driverFile);

            TestResult result = generator.run();

            LOGGER.info("Writing test result into {}", resultFile.getAbsolutePath());
            WRITER.writeValue(resultFile, result);

            complete = true;
        } catch (Exception e) {
            if (!complete) {
                LOGGER.error("Failed to run the workload '{}' for driver '{}'", workload.name, driverFile.getAbsolutePath(), e);
                throw e;
            }
            LOGGER.warn("Failed to cleanly shutdown the workload '{}' for driver '{}'", workload.name, driverFile.getAbsolutePath(), e);
        }

        TestMetadataCapture.getInstance().storeOmbData(ombCluster, workload, driver, this);

        return new OMBWorkloadResult(resultFile, createTestResult(resultFile));
    }

    public void uninstall() throws IOException {
        LOGGER.info("Deleting namespace {}", Constants.OMB_NAMESPACE);
        ombCluster.waitForDeleteNamespace(Constants.OMB_NAMESPACE);
    }

    public KubeClusterResource getOmbCluster() {
        return ombCluster;
    }

    /**
     * Delete worker deployments.
     */
    public void deleteWorkers() throws Exception {
        LOGGER.info("Deleting {} workers", workerNames.size());
        OpenShiftClient client = ombCluster.kubeClient().client().adapt(OpenShiftClient.class);

        client.apps().deployments().inNamespace(Constants.OMB_NAMESPACE).delete();
        client.services().inNamespace(Constants.OMB_NAMESPACE).delete();
        client.routes().inNamespace(Constants.OMB_NAMESPACE).delete();

        while (!client.apps().deployments().inNamespace(Constants.OMB_NAMESPACE).list().getItems().isEmpty()) {
            Thread.sleep(5000);
        }
        while (!client.pods().inNamespace(Constants.OMB_NAMESPACE).list().getItems().isEmpty()) {
            Thread.sleep(5000);
        }
        while (!client.services().inNamespace(Constants.OMB_NAMESPACE).list().getItems().isEmpty()) {
            Thread.sleep(5000);
        }
        while (!client.routes().inNamespace(Constants.OMB_NAMESPACE).list().getItems().isEmpty()) {
            Thread.sleep(5000);
        }
        LOGGER.info("Deleted {} workers", workerNames.size());
        workerNames.clear();
    }

    private TestResult createTestResult(File file) throws IOException {
        return new ObjectMapper().readValue(file, TestResult.class);
    }

    /*
     * This Daemonset just forces the image to remain on all of the
     * worker nodes, so that there isn't any kind of pull storm (and
     * worker deploys will be slightly quicker). This DaemonSet (and
     * namespace) don't get cleaned up by the test, as it seems
     * beneficial to keep the image on the nodes between tests.
     */
    private void pullAndHoldWorkerImageToAllNodesUsingDaemonSet() {
        String ombWorkerImageHolder = "omb-worker-image-holder";

        ombCluster.kubeClient().client().namespaces()
            .createOrReplace(new NamespaceBuilder().withNewMetadata().withName(ombWorkerImageHolder).endMetadata().build());

        ombCluster.kubeClient().client().apps().daemonSets().inNamespace(ombWorkerImageHolder)
            .createOrReplace(new DaemonSetBuilder()
                             .withNewMetadata().withName(ombWorkerImageHolder).endMetadata()
                             .withNewSpec()
                             .withNewSelector()
                             .addToMatchLabels("app", ombWorkerImageHolder)
                             .endSelector()
                             .withNewTemplate()
                             .withNewMetadata().withLabels(Map.of("app", ombWorkerImageHolder)).endMetadata()
                             .withNewSpec()
                             .addNewContainer()
                             .withName(ombWorkerImageHolder)
                             .withImage(Constants.OMB_WORKER_IMAGE)
                             .withCommand("sh", "-c")
                             .withArgs("tail -f /dev/null")
                             .withNewResources()
                             .addToRequests(Collections.singletonMap("memory", new Quantity("12Mi")))
                             .addToLimits(Collections.singletonMap("memory", new Quantity("50Mi")))
                             .endResources()
                             .endContainer()
                             .withTerminationGracePeriodSeconds(5L)
                             .endSpec()
                             .endTemplate()
                             .endSpec()
                             .build());

        try {
            LOGGER.info("Waiting for DaemonSet to become ready");
            DaemonSetStatus daemonSetStatus;
            do {
                Thread.sleep(5000);
                daemonSetStatus = ombCluster.kubeClient().client().apps().daemonSets().inNamespace(ombWorkerImageHolder)
                    .withName(ombWorkerImageHolder).get().getStatus();
                LOGGER.info("DaemonSet reporting {} pods ready, expecting {}", daemonSetStatus.getNumberReady(), daemonSetStatus.getDesiredNumberScheduled());
            } while (!Objects.equals(daemonSetStatus.getNumberReady(), daemonSetStatus.getDesiredNumberScheduled()));
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to wait for DaemonSet to become ready, received InterruptedException", e);
        }
    }

    public void setUseSingleNode(boolean single) {
        this.useSingleNode = single;
    }
}
