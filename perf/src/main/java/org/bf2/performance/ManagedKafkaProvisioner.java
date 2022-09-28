package org.bf2.performance;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.client.OpenShiftClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.common.OperandUtils;
import org.bf2.operator.operands.KafkaInstanceConfiguration;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacity;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacityBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.performance.framework.TestMetadataCapture;
import org.bf2.systemtest.api.sync.SyncApiClient;
import org.bf2.systemtest.framework.SecurityUtils;
import org.bf2.systemtest.framework.SecurityUtils.TlsConfig;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Provisioner for {@link ManagedKafka} instances
 */
public class ManagedKafkaProvisioner {

    public static final String KAFKA_NAMESPACE = "kafka";
    private static final String KAS_FLEETSHARD_CONFIG = "kas-fleetshard-config";
    public static final String KAFKA_BROKER_TAINT_KEY = "org.bf2.operator/kafka-broker";
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaProvisioner.class);
    private List<ManagedKafka> clusters = new ArrayList<>();
    protected KubeClusterResource cluster;
    protected String domain;
    private TlsConfig tlsConfig;
    private OlmBasedStrimziOperatorManager strimziManager;
    private SharedIndexInformer<Deployment> informer;

    private List<String> strimziVersions;

    private Resource<ManagedKafkaAgent> agentResource;

    static String determineDomain(KubeClusterResource cluster) throws IOException {
        var client = cluster.kubeClient().client().adapt(OpenShiftClient.class).operator().ingressControllers();
        String defaultDomain = client.inNamespace(Constants.OPENSHIFT_INGRESS_OPERATOR)
                .withName("default")
                .get()
                .getStatus()
                .getDomain();
        return defaultDomain.replace("apps", "kas"); // use the sharded domain
    }

    /**
     * Create a kafka provisioner for a given cluster.
     */
    static ManagedKafkaProvisioner create(KubeClusterResource cluster) throws IOException {
        TestMetadataCapture.getInstance().storeKafkaOpenshiftData(cluster);
        return new ManagedKafkaProvisioner(cluster);
    }

    static ConfigMap toConfigMap(KafkaInstanceConfiguration profile, Map<String, String> baseProperties) throws IOException {
        Map<String, String> allProperties = new LinkedHashMap<>(baseProperties);

        Map<String, String> propertyMap = profile.toMap(false);
        allProperties.putAll(propertyMap);

        ConfigMap override =
                new ConfigMapBuilder().withNewMetadata()
                        .withName(KAS_FLEETSHARD_CONFIG)
                        .endMetadata()
                        .withData(allProperties)
                        .build();
        return override;
    }

    public ManagedKafkaCapacity defaultCapacity(long ingressEgressThroughput) {
        ManagedKafkaCapacityBuilder capacityBuilder = new ManagedKafkaCapacityBuilder();
        capacityBuilder.withIngressEgressThroughputPerSec(Quantity.parse(String.valueOf(ingressEgressThroughput)));
        // TODO: this value is roughly 3x the old value from KafkaConfigurations
        // should probably default to Value Prod instead
        capacityBuilder.withMaxDataRetentionSize(Quantity.parse("600Gi"));

        return capacityBuilder.build();
    }

    ManagedKafkaProvisioner(KubeClusterResource cluster) {
        this.cluster = cluster;
        this.strimziManager =
                new OlmBasedStrimziOperatorManager(cluster.kubeClient(), StrimziOperatorManager.OPERATOR_NS);
    }

    /**
     * One-time setup of provisioner. This should be called only once per test class.
     */
    public void setup() throws Exception {
        this.domain = determineDomain(cluster);
        File tls = new File("target", domain + "-tls.json");
        if (tls.exists()) {
            try (FileInputStream fis = new FileInputStream(tls)) {
                this.tlsConfig = Serialization.unmarshal(fis, SecurityUtils.TlsConfig.class);
            }
        } else {
            this.tlsConfig = SecurityUtils.getTLSConfig(domain);
            try (FileOutputStream fos = new FileOutputStream(tls)) {
                fos.write(Serialization.asYaml(this.tlsConfig).getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            this.clusters.addAll(cluster.kubeClient()
                    .client()
                    .resources(ManagedKafka.class)
                    .inNamespace(KAFKA_NAMESPACE)
                    .list()
                    .getItems());
        } catch (KubernetesClientException e) {

        }
        agentResource = this.cluster.kubeClient()
                .client()
                .resources(ManagedKafkaAgent.class)
                .inNamespace(FleetShardOperatorManager.OPERATOR_NS)
                .withName(ManagedKafkaAgentResourceClient.RESOURCE_NAME);
    }

    public TlsConfig getTlsConfig() {
        return tlsConfig;
    }

    /**
     * One-time teardown of provisioner. This should be called only once per test class.
     */
    public void teardown() throws Exception {
        if (informer != null) {
            informer.stop();
        }
    }

    /**
     * Get the kubernetes cluster handle for this provisioner.
     */
    public KubeClusterResource getKubernetesCluster() {
        return cluster;
    }

    /**
     * Install this Kafka provisioner. This can be called once per test class or per test method.
     */
    public void install() throws Exception {
        // delete/create the namespaces to be used
        Map<String, String> nsAnnotations = new HashMap<>();
        if (PerformanceEnvironment.KAFKA_COLLECT_LOG) {
            nsAnnotations.put(Constants.ORG_BF2_KAFKA_PERFORMANCE_COLLECTPODLOG, "true");
        }
        cluster.waitForDeleteNamespace(StrimziOperatorManager.OPERATOR_NS);
        FleetShardOperatorManager.deleteFleetShard(cluster.kubeClient()).get(2, TimeUnit.MINUTES);
        cluster.createNamespace(KAFKA_NAMESPACE, nsAnnotations, Map.of());

        List<Node> workers = cluster.getWorkerNodes();

        boolean smallNodes = workers.stream().anyMatch(n -> TestUtils.getMaxAvailableResources(n).cpuMillis < 3000);

        if (smallNodes) {
            MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments =
                    cluster.kubeClient().client().apps().deployments();
            this.informer = deployments.inAnyNamespace().inform(new ResourceEventHandler<Deployment>() {

                @Override
                public void onUpdate(Deployment oldObj, Deployment newObj) {
                    onAdd(newObj);
                }

                @Override
                public void onDelete(Deployment obj, boolean deletedFinalStateUnknown) {

                }

                @Override
                public void onAdd(Deployment obj) {
                    if (!obj.getMetadata().getNamespace().equals(StrimziOperatorManager.OPERATOR_NS)
                            && !obj.getMetadata().getNamespace().equals(FleetShardOperatorManager.OPERATOR_NS)) {
                        return;
                    }

                    // patch any deployment that requests a lot of cpu, and make sure it's on the perf infra
                    deployments.inNamespace(obj.getMetadata().getNamespace())
                            .withName(obj.getMetadata().getName())
                            .edit(
                                    new TypedVisitor<ResourceRequirementsBuilder>() {
                                        @Override
                                        public void visit(ResourceRequirementsBuilder element) {
                                            Quantity cpu = null;
                                            if (element.getRequests() != null) {
                                                cpu = element.getRequests().get("cpu");
                                            }
                                            if (cpu == null && element.getLimits() != null) {
                                                cpu = element.getLimits().get("cpu");
                                            }
                                            if (cpu != null && Quantity.getAmountInBytes(cpu)
                                                    .compareTo(BigDecimal.valueOf(1)) > 0) {
                                                element.addToRequests("cpu", Quantity.parse("1"));
                                            }
                                        }
                                    });
                }
            });
        }

        // installs the Strimzi Operator using the OLM bundle
        CompletableFuture<Void> strimziFuture = strimziManager.deployStrimziOperator();

        cluster.connectNamespaceToMonitoringStack(StrimziOperatorManager.OPERATOR_NS);

        // installs a cluster wide fleetshard operator
        // not looking at the returned futures - it's assumed that we'll eventually wait on the managed kafka deployment
        CompletableFuture<Void> future = FleetShardOperatorManager.deployFleetShardOperator(cluster.kubeClient());
        CompletableFuture.allOf(future, strimziFuture).get(2, TimeUnit.MINUTES);

        var agentResource = this.cluster.kubeClient()
                .client()
                .resource(new ManagedKafkaAgentBuilder().withNewMetadata()
                        .withName(ManagedKafkaAgentResourceClient.RESOURCE_NAME)
                        .withNamespace(FleetShardOperatorManager.OPERATOR_NS)
                        .endMetadata()
                        .withSpec(new ManagedKafkaAgentSpecBuilder()
                                .withNewObservability()
                                .withAccessToken("")
                                .withChannel("")
                                .withRepository("")
                                .withTag("")
                                .endObservability()
                                .build())
                        .build());
        agentResource.createOrReplace();

        //FleetShardOperatorManager.deployFleetShardSync(cluster.kubeClient());
        cluster.connectNamespaceToMonitoringStack(FleetShardOperatorManager.OPERATOR_NS);

        strimziVersions =
                SyncApiClient.getSortedAvailableStrimziVersions(() -> agentResource.fromServer().get().getStatus())
                        .collect(Collectors.toList());
    }

    /**
     * TODO: if/when this will need to test bin packing, then we'll separate the profile setting from deployCluster
     *
     * Deploy a Kafka cluster using this provisioner.
     * @param profile
     */
    public ManagedKafkaDeployment deployCluster(String name, ManagedKafkaCapacity managedKafkaCapacity,
            KafkaInstanceConfiguration profile) throws Exception {
        return deployCluster(new ObjectMetaBuilder().withName(name).build(), managedKafkaCapacity, profile, Collections.emptyMap());
    }

    /**
     * TODO: if/when this will need to test bin packing, then we'll separate the profile setting from deployCluster
     *
     * Deploy a Kafka cluster using this provisioner.
     * @param profile
     */
    public ManagedKafkaDeployment deployCluster(ObjectMeta meta, ManagedKafkaCapacity managedKafkaCapacity,
            KafkaInstanceConfiguration profile, Map<String, String> baseProperties) throws Exception {
        // set and validate the strimzi version
        String strimziVersion = PerformanceEnvironment.STRIMZI_VERSION;
        if (strimziVersion == null) {
            strimziVersion = strimziVersions.get(strimziVersions.size() - 1);
        }
        String kafkaVersion = PerformanceEnvironment.KAFKA_VERSION;
        if (kafkaVersion == null) {
            kafkaVersion = getKafkaVersion(strimziVersion);
        }
        List<String> versions = strimziManager.getVersions();
        if (!versions.contains(strimziVersion)) {
            throw new IllegalStateException(String
                    .format("Strimzi version %s is not in the set of installed versions %s", strimziVersion, versions));
        }

        Integer replicas = profile.getKafka().getReplicasOverride();
        if (replicas == null) {
            replicas = 3;
            profile.getKafka().setReplicasOverride(replicas);
        }

        applyProfile(profile, replicas, baseProperties);

        String namespace = KAFKA_NAMESPACE;

        ManagedKafka managedKafka = new ManagedKafkaBuilder()
                .withMetadata(meta).editMetadata()
                .withNamespace(namespace)
                .endMetadata()
                .withSpec(new ManagedKafkaSpecBuilder().withCapacity(managedKafkaCapacity)
                        .withNewEndpoint()
                        .withBootstrapServerHost(String.format("%s-kafka-bootstrap-%s.%s", meta.getName(), namespace, domain))
                        .withNewTls()
                        .withCert(tlsConfig.getCert())
                        .withKey(tlsConfig.getKey())
                        .endTls()
                        .endEndpoint()
                        .withNewVersions()
                        .withKafka(kafkaVersion)
                        .withStrimzi(strimziVersion)
                        .endVersions()
                        .build())
                .build();

        clusters.add(managedKafka);

        LOGGER.info("Deploying {}", Serialization.asYaml(managedKafka));

        ManagedKafkaDeployment kafkaDeployment = deployCluster(namespace, managedKafka);
        kafkaDeployment.start();
        return kafkaDeployment;
    }

    private String getKafkaVersion(String strimziVersion) {
        return agentResource.get()
                .getStatus()
                .getStrimzi()
                .stream()
                .filter(v -> strimziVersion.equals(v.getVersion()))
                .findFirst()
                .map(v -> v.getKafkaVersions().get(v.getKafkaVersions().size() - 1))
                .orElseThrow(() -> new IllegalStateException("Could not find kafka version for " + strimziVersion));
    }

    /**
     * Removes kafka cluster
     *
     * @throws IOException
     */
    public void removeClusters(boolean all) throws IOException {
        var client = cluster.kubeClient().client().resources(ManagedKafka.class).inNamespace(KAFKA_NAMESPACE);
        List<ManagedKafka> kafkas = clusters;
        if (all) {
            kafkas = client.list().getItems();
        }
        Iterator<ManagedKafka> kafkaIterator = clusters.iterator();
        while (kafkaIterator.hasNext()) {
            ManagedKafka k = kafkaIterator.next();
            LOGGER.info("Removing cluster {}", k.getMetadata().getName());
            client.withName(k.getMetadata().getName()).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
        }
        for (ManagedKafka k : kafkas) {
            org.bf2.test.TestUtils.waitFor("await delete deployment", 1_000, 600_000,
                    () -> client.withName(k.getMetadata().getName()).get() == null);
        }
        clusters.clear();
    }

    public void removeTaintsOnNodes() {
        List<Node> workers = cluster.getWorkerNodes();

        workers.stream()
                .forEach(n -> cluster.kubeClient()
                        .client()
                        .nodes()
                        .withName(n.getMetadata().getName())
                        .patch(PatchContext.of(PatchType.STRATEGIC_MERGE),
                                new NodeBuilder()
                                        .editOrNewSpec()
                                        .removeMatchingFromTaints(t -> t.getKey().equals(KAFKA_BROKER_TAINT_KEY))
                                        .endSpec()
                                        .build()));
    }

    /**
     * Uninstall this provisioner from the system. This  will also delete all Kafka clusters created by
     * the provisioner. This can be called once per test class or per test method.
     */
    public void uninstall() throws Exception {
        removeClusters(false);
        strimziManager.deleteStrimziOperator();
        FleetShardOperatorManager.deleteFleetShard(cluster.kubeClient());
        LOGGER.info("Deleting namespace {}", KAFKA_NAMESPACE);
        cluster.waitForDeleteNamespace(KAFKA_NAMESPACE);
        removeTaintsOnNodes();
    }

    void applyProfile(KafkaInstanceConfiguration profile, int replicas, Map<String, String> baseProperties) throws IOException {
        if (!this.clusters.isEmpty()) {
            // until install applies the profile, we can only do one deployment at a time
            throw new IllegalStateException("the provisioner cannot currently manage multiple clusters");
        }

        // remove any previous taints.
        removeTaintsOnNodes();

        List<Node> workers = cluster.getWorkerNodes();

        // divide the nodes by their zones, then sort them by their cpu availability then mark however brokers needed for the taint
        validateClusterForBrokers(replicas, profile.getKafka().isColocateWithZookeeper(),
                workers.stream());

        // convert the profile into simple configmap values
        ConfigMap override = toConfigMap(profile, baseProperties);

        var configMapClient =
                cluster.kubeClient().client().configMaps().inNamespace(FleetShardOperatorManager.OPERATOR_NS);
        configMapClient.createOrReplace(override);

        LOGGER.info("Restarting fleetshard operatior with configuration {}", Serialization.asYaml(override));

        // restart the operator deployment
        cluster.kubeClient()
                .client()
                .pods()
                .inNamespace(FleetShardOperatorManager.OPERATOR_NS)
                .withLabel("app", "kas-fleetshard-operator").delete();
    }

    public void validateClusterForBrokers(int brokers, boolean colocateWithZookeeper, Stream<Node> workers) {
        Map<String, List<Node>> zoneAwareNodeList = workers
                .collect(Collectors.groupingBy(n -> n.getMetadata().getLabels().get("topology.kubernetes.io/zone")));
        zoneAwareNodeList.values()
                .forEach(list -> Collections.sort(list,
                        (n1, n2) -> Long.compare(TestUtils.getMaxAvailableResources(n1).cpuMillis,
                                TestUtils.getMaxAvailableResources(n2).cpuMillis)));

        int brokersPerZone = brokers / zoneAwareNodeList.size();
        for (List<Node> nodes : zoneAwareNodeList.values()) {
            if (nodes.size() < brokersPerZone) {
                throw new IllegalStateException("Not enough nodes per zone available");
            }

            for (int i = 1; i <= brokersPerZone; i++) {
                Node n = nodes.get(nodes.size() - i);

                if (!colocateWithZookeeper) {
                    cluster.kubeClient()
                            .client()
                            .nodes()
                            .withName(n.getMetadata().getName())
                            .patch(PatchContext.of(PatchType.STRATEGIC_MERGE),
                                    new NodeBuilder()
                                            .editOrNewSpec()
                                            .addNewTaint()
                                            .withKey(KAFKA_BROKER_TAINT_KEY)
                                            .withEffect("NoExecute")
                                            .endTaint()
                                            .endSpec()
                                            .build());
                }
            }
        }
    }

    ManagedKafkaDeployment deployCluster(String namespace, ManagedKafka managedKafka) throws Exception {
        var configMapClient = cluster.kubeClient().client().configMaps().inNamespace(namespace);

        // set kafka and zookeeper metrics
        if (PerformanceEnvironment.ENABLE_METRICS) {
            ConfigMap kafkaMetrics = configMapClient
                    .load(ManagedKafkaProvisioner.class.getClassLoader().getResource("kafka-metrics.yaml"))
                    .get();
            kafkaMetrics.getMetadata().setName(managedKafka.getMetadata().getName() + "-kafka-metrics");
            configMapClient.createOrReplace(kafkaMetrics);

            ConfigMap zookeeperMetrics = configMapClient
                    .load(ManagedKafkaProvisioner.class.getClassLoader().getResource("zookeeper-metrics.yaml"))
                    .get();
            zookeeperMetrics.getMetadata().setName(managedKafka.getMetadata().getName() + "-zookeeper-metrics");
            configMapClient.createOrReplace(zookeeperMetrics);
        }

        // create the managed kafka
        var managedKakfaClient = cluster.kubeClient().client().resources(ManagedKafka.class);

        // create a dummy master secret - normally this is the syncs job
        cluster.kubeClient()
                .client()
                .secrets()
                .inNamespace(namespace)
                .createOrReplace(new SecretBuilder().withNewMetadata()
                        .withName(OperandUtils.masterSecretName(managedKafka))
                        .endMetadata()
                        .build());

        managedKafka = managedKakfaClient.inNamespace(namespace).createOrReplace(managedKafka);

        return new ManagedKafkaDeployment(managedKafka, cluster);
    }

    public ManagedKafka getCluster(String name) {
        Resource<ManagedKafka> mkResource = cluster.kubeClient()
                .client()
                .resources(ManagedKafka.class)
                .inNamespace(KAFKA_NAMESPACE)
                .withName(name);
        try {
            return mkResource.get();
        } catch (KubernetesClientException e) {

        }
        return null;
    }

}
