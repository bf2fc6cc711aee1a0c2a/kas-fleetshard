package org.bf2.performance;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.client.OpenShiftClient;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.operands.KafkaInstanceConfiguration;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacity;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacityBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.performance.framework.TestMetadataCapture;
import org.bf2.systemtest.framework.SecurityUtils;
import org.bf2.systemtest.framework.SecurityUtils.TlsConfig;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Provisioner for {@link ManagedKafka} instances
 */
public class ManagedKafkaProvisioner {

    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaProvisioner.class);
    private final List<ManagedKafka> clusters = new ArrayList<>();
    protected KubeClusterResource cluster;
    protected String domain;
    private TlsConfig tlsConfig;
    private OlmBasedStrimziOperatorManager strimziManager;
    private SharedIndexInformer<Deployment> informer;

    static String createIngressController(KubeClusterResource cluster) throws IOException {
        var client = cluster.kubeClient().client().adapt(OpenShiftClient.class).operator().ingressControllers();
        String defaultDomain = client.inNamespace(Constants.OPENSHIFT_INGRESS_OPERATOR).withName("default").get().getStatus().getDomain();

        return defaultDomain;
        /*
        String domain = defaultDomain.replace("apps", "bf2");
        Map<String, String> routeSelectorLabel = Collections.singletonMap("ingressType", "sharded");
        IngressController ingressController = new IngressControllerBuilder()
                .editOrNewMetadata()
                .withName("sharded-nlb")
                .withNamespace(Constants.OPENSHIFT_INGRESS_OPERATOR)
                .endMetadata()
                .editOrNewSpec()
                .withDomain(domain)
                .withRouteSelector(new LabelSelector(null, routeSelectorLabel))
                .withReplicas(3)
                .withNewNodePlacement()
                .editOrNewNodeSelector()
                .addToMatchLabels("node-role.kubernetes.io/worker", "")
                .endNodeSelector()
                .endNodePlacement()
                .withNewEndpointPublishingStrategy()
                .withType("LoadBalancerService")
                .withNewLoadBalancer()
                .withScope("External")
                .withNewProviderParameters()
                .withType("AWS")
                .withNewAws()
                .withType("NLB")
                .endAws()
                .endProviderParameters()
                .endLoadBalancer()
                .endEndpointPublishingStrategy()
                .endSpec()
                .build();

        client.inNamespace(Constants.OPENSHIFT_INGRESS_OPERATOR)
                .createOrReplace(ingressController);

        return new RouterConfig(domain, routeSelectorLabel);
        */
    }

    static void deleteIngressController(KubeClusterResource cluster) {
        cluster.kubeClient().client().adapt(OpenShiftClient.class).operator().ingressControllers().withName("sharded-nlb").delete();
    }

    /**
     * Create a kafka provisioner for a given cluster.
     */
    static ManagedKafkaProvisioner create(KubeClusterResource cluster) throws IOException {
        TestMetadataCapture.getInstance().storeKafkaOpenshiftData(cluster);
        return new ManagedKafkaProvisioner(cluster);
    }

    static ConfigMap toConfigMap(KafkaInstanceConfiguration profile) throws IOException {
        Map<String,String> propertyMap = profile.toMap(false);

        ConfigMap override =
                new ConfigMapBuilder().withNewMetadata().withName("kas-fleetshard-config").endMetadata()
                        .withData(propertyMap).build();
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
        this.strimziManager = new OlmBasedStrimziOperatorManager(cluster.kubeClient(), StrimziOperatorManager.OPERATOR_NS);
    }

    /**
     * One-time setup of provisioner. This should be called only once per test class.
     */
    public void setup() throws Exception {
        this.domain = createIngressController(cluster);
        this.tlsConfig = SecurityUtils.getTLSConfig(domain);
    }

    public TlsConfig getTlsConfig() {
        return tlsConfig;
    }

    /**
     * One-time teardown of provisioner. This should be called only once per test class.
     */
    public void teardown() throws Exception {
        deleteIngressController(cluster);
        informer.stop();
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
        cluster.createNamespace(Constants.KAFKA_NAMESPACE, Map.of(), Map.of());
        cluster.waitForDeleteNamespace(StrimziOperatorManager.OPERATOR_NS);
        cluster.waitForDeleteNamespace(FleetShardOperatorManager.OPERATOR_NS);

        List<Node> workers = cluster.getWorkerNodes();

        // until we have node taints/tolerations on the brokers, make sure that the operators and other deployments are on just 3 nodes, so build a list of 1 node per az
        Collection<Node> nodes = workers.stream().sorted((n1, n2) -> n1.getMetadata().getName().compareTo(n2.getMetadata().getName()))
                .collect(Collectors.toMap(n -> n.getMetadata().getLabels().get("topology.kubernetes.io/zone"), Function.identity(), (n1, n2) -> n1)).values();
        nodes.forEach(n -> cluster.kubeClient().client().nodes().withName(n.getMetadata().getName())
                .patch(PatchContext.of(PatchType.STRATEGIC_MERGE),
                        new NodeBuilder().editOrNewMetadata().addToLabels("perf-infra", "true").endMetadata().build()));

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deployments = cluster.kubeClient().client().apps().deployments();
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
                        .withName(obj.getMetadata().getName()).edit(
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
                                        if (cpu != null && Quantity.getAmountInBytes(cpu).compareTo(BigDecimal.valueOf(1)) > 0) {
                                            element.addToRequests("cpu", Quantity.parse("1"));
                                        }
                                    }
                                },

                                new TypedVisitor<PodTemplateSpecBuilder>() {
                                    @Override
                                    public void visit(PodTemplateSpecBuilder element) {
                                        element.editOrNewSpec().addToNodeSelector("perf-infra", "true").endSpec();
                                    }
                                });
            }
        });

        // installs the Strimzi Operator using the OLM bundle
        strimziManager.deployStrimziOperator();

        cluster.connectNamespaceToMonitoringStack(StrimziOperatorManager.OPERATOR_NS);

        // installs a cluster wide fleetshard operator
        // not looking at the returned futures - it's assumed that we'll eventually wait on the managed kafka deployment
        CompletableFuture<Void> future = FleetShardOperatorManager.deployFleetShardOperator(cluster.kubeClient());
        future.get(120_000, TimeUnit.SECONDS);
        //FleetShardOperatorManager.deployFleetShardSync(cluster.kubeClient());
        cluster.connectNamespaceToMonitoringStack(FleetShardOperatorManager.OPERATOR_NS);
    }

    /**
     * TODO: if/when this will need to test bin packing, then we'll separate the profile setting from deployCluster
     *
     * Deploy a Kafka cluster using this provisioner.
     * @param profile
     */
    public ManagedKafkaDeployment deployCluster(String name, ManagedKafkaCapacity managedKafkaCapacity, KafkaInstanceConfiguration profile) throws Exception {
        // set and validate the strimzi version
        String strimziVersion = PerformanceEnvironment.STRIMZI_VERSION;
        if (strimziVersion == null) {
            strimziVersion = strimziManager.getCurrentVersion();
        }
        List<String> versions = strimziManager.getVersions();
        if (!versions.contains(strimziVersion)) {
            throw new IllegalStateException(String.format("Strimzi version %s is not in the set of installed versions %s", strimziVersion, versions));
        }
        applyProfile(profile);

        String namespace = Constants.KAFKA_NAMESPACE;

        ManagedKafka managedKafka = new ManagedKafkaBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
                .withSpec(new ManagedKafkaSpecBuilder().withCapacity(managedKafkaCapacity)
                        .withNewEndpoint()
                        .withBootstrapServerHost(String.format("%s-kafka-bootstrap-%s.%s", name, namespace, domain))
                        .withNewTls()
                        .withCert(tlsConfig.getCert())
                        .withKey(tlsConfig.getKey())
                        .endTls()
                        .endEndpoint()
                        .withNewVersions()
                        .withKafka(PerformanceEnvironment.KAFKA_VERSION)
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

    /**
     * Removes kafka cluster
     *
     * @throws IOException
     */
    public void removeClusters() throws IOException {
        var client = cluster.kubeClient().client().resources(ManagedKafka.class);
        Iterator<ManagedKafka> kafkaIterator = clusters.iterator();
        while (kafkaIterator.hasNext()) {
            ManagedKafka k = kafkaIterator.next();
            LOGGER.info("Removing cluster {}", k.getMetadata().getName());
            client.inNamespace(Constants.KAFKA_NAMESPACE).withName(k.getMetadata().getName()).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
        }
        kafkaIterator = clusters.iterator();
        while (kafkaIterator.hasNext()) {
            ManagedKafka k = kafkaIterator.next();
            org.bf2.test.TestUtils.waitFor("await delete deployment", 1_000, 600_000, () -> client.inNamespace(Constants.KAFKA_NAMESPACE).withName(k.getMetadata().getName()).get() == null);
            kafkaIterator.remove();
        }
    }

    /**
     * Uninstall this provisioner from the system. This  will also delete all Kafka clusters created by
     * the provisioner. This can be called once per test class or per test method.
     */
    public void uninstall() throws Exception {
        removeClusters();
        strimziManager.deleteStrimziOperator();
        FleetShardOperatorManager.deleteFleetShard(cluster.kubeClient());
        LOGGER.info("Deleting namespace {}", Constants.KAFKA_NAMESPACE);
        cluster.waitForDeleteNamespace(Constants.KAFKA_NAMESPACE);
    }

    void applyProfile(KafkaInstanceConfiguration profile) throws IOException {
        // convert the profile into simple configmap values
        ConfigMap override = toConfigMap(profile);

        var configMapClient = cluster.kubeClient().client().configMaps().inNamespace(FleetShardOperatorManager.OPERATOR_NS);
        configMapClient.createOrReplace(override);

        // restart the operator deployment
        RollableScalableResource<Deployment> fleetshardOperatorDeployment = cluster.kubeClient()
                .client()
                .apps()
                .deployments()
                .inNamespace(FleetShardOperatorManager.OPERATOR_NS)
                .withName(FleetShardOperatorManager.OPERATOR_NAME);
        LOGGER.info("Restarting fleetshard operatior with configuration {}", Serialization.asYaml(override));

        fleetshardOperatorDeployment.scale(0, true);
        fleetshardOperatorDeployment.scale(1, true);
    }

    ManagedKafkaDeployment deployCluster(String namespace, ManagedKafka managedKafka) throws Exception {
        var configMapClient = cluster.kubeClient().client().configMaps().inNamespace(namespace);

        // set kafka and zookeeper metrics
        if (PerformanceEnvironment.ENABLE_METRICS) {
            ConfigMap kafkaMetrics = configMapClient.load(ManagedKafkaProvisioner.class.getClassLoader().getResource("kafka-metrics.yaml")).get();
            kafkaMetrics.getMetadata().setName(managedKafka.getMetadata().getName() + "-kafka-metrics");
            configMapClient.createOrReplace(kafkaMetrics);

            ConfigMap zookeeperMetrics = configMapClient.load(ManagedKafkaProvisioner.class.getClassLoader().getResource("zookeeper-metrics.yaml")).get();
            zookeeperMetrics.getMetadata().setName(managedKafka.getMetadata().getName() + "-zookeeper-metrics");
            configMapClient.createOrReplace(zookeeperMetrics);
        }

        // create the managed kafka
        var managedKakfaClient = cluster.kubeClient().client().resources(ManagedKafka.class);

        managedKafka = managedKakfaClient.inNamespace(namespace).createOrReplace(managedKafka);

        var kafkaClient = cluster.kubeClient().client().resources(Kafka.class).inNamespace(namespace).withName(managedKafka.getMetadata().getName());

        org.bf2.test.TestUtils.waitFor("kafka resource", 1_000, 300_000, () -> kafkaClient.get() != null);

        // track the result
        Kafka kafka = kafkaClient.require();
        LOGGER.info("Created Kafka {}", Serialization.asYaml(kafka));
        return new ManagedKafkaDeployment(managedKafka, kafka, cluster);
    }

}
