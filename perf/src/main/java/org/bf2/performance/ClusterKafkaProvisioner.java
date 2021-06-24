package org.bf2.performance;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Abstraction for managing strimzi/kafka
 */
public class ClusterKafkaProvisioner implements KafkaProvisioner {
    private static final Logger LOGGER = LogManager.getLogger(ClusterKafkaProvisioner.class);
    private final List<ManagedKafka> clusters = new ArrayList<>();
    protected KubeClusterResource cluster;
    protected List<RouterConfig> routerConfigs;

    ClusterKafkaProvisioner(KubeClusterResource cluster) {
        this.cluster = cluster;
    }

    @Override
    public void setup() throws Exception {
        this.routerConfigs = createIngressControllers(cluster, Environment.NUM_INGRESS_CONTROLLERS);
    }

    @Override
    public void teardown() throws Exception {
        deleteIngressController(cluster);
    }

    @Override
    public KubeClusterResource getKubernetesCluster() {
        return cluster;
    }

    @Override
    public void install() throws Exception {
        // delete/create the namespaces to be used
        cluster.createNamespace(Constants.KAFKA_NAMESPACE, Map.of(), Map.of());
        cluster.waitForDeleteNamespace(StrimziOperatorManager.OPERATOR_NS);
        cluster.waitForDeleteNamespace(FleetShardOperatorManager.OPERATOR_NS);

        // installs the Strimzi Operator using the OLM bundle
        OlmBasedStrimziOperatorManager.deployStrimziOperator(cluster.kubeClient(), StrimziOperatorManager.OPERATOR_NS);

        cluster.connectNamespaceToMonitoringStack(StrimziOperatorManager.OPERATOR_NS);

        // installs a cluster wide fleetshard operator
        // TODO: I'm not looking at the returned futures - it's assumed that we'll eventually wait on the managed kafka deployment
        FleetShardOperatorManager.deployFleetShardOperator(cluster.kubeClient());
        cluster.connectNamespaceToMonitoringStack(FleetShardOperatorManager.OPERATOR_NS);
        //FleetShardOperatorManager.deployFleetShardSync(cluster.kubeClient());

    }

    @Override
    public KafkaDeployment deployCluster(ManagedKafka managedKafka, AdopterProfile profile) throws Exception {
        int clusterNumber = 1;
        RouterConfig routerConfig = routerConfigs.get(clusterNumber % routerConfigs.size());

        clusters.add(managedKafka);

        // TODO: should this be a fixed namespace.  The assumption currently is that the operator and managedkafka will be in the same namespace
        String namespace = Constants.KAFKA_NAMESPACE;

        managedKafka.getMetadata().setNamespace(namespace);
        // TODO: the operator is not doing this, just setting ingressType to sharded
        // kafka.getMetadata().getLabels().putAll(routerConfig.getRouteSelectorLabels());

        LOGGER.info("Cluster {} deploy with domain {}", managedKafka.getMetadata().getName(), routerConfig.getDomain());

        // convert the profile into simple configmap values - the operator should restart with these values
        ConfigMap override = toConfigMap(profile);
        cluster.kubeClient().client().configMaps().inNamespace(namespace).createOrReplace(override);
        // TODO: bounce the operator - or move the profile to install

        KafkaDeployment kafkaDeployment = deployCluster(namespace, managedKafka, routerConfig.getDomain());
        kafkaDeployment.start();
        return kafkaDeployment;
    }

    static ConfigMap toConfigMap(AdopterProfile profile) throws IOException {
        Properties propertyMap = Serialization.jsonMapper().convertValue(profile, Properties.class);
        StringWriter writer = new StringWriter();
        propertyMap.store(writer, null);

        ConfigMap override =
                new ConfigMapBuilder().withNewMetadata().withName("operator-logging-config-override").endMetadata()
                .withData(Collections.singletonMap("application.properties", writer.toString())).build();
        return override;
    }

    /**
     * Removes kafka cluster
     *
     * @throws IOException
     */
    private void removeClusters() throws IOException {
        var client = cluster.kubeClient().client().customResources(ManagedKafka.class);
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

    @Override
    public void uninstall() throws Exception {
        removeClusters();
        OlmBasedStrimziOperatorManager.deleteStrimziOperator(cluster.kubeClient(), StrimziOperatorManager.OPERATOR_NS);
        FleetShardOperatorManager.deleteFleetShard(cluster.kubeClient());
        LOGGER.info("Deleting namespace {}", Constants.KAFKA_NAMESPACE);
        cluster.waitForDeleteNamespace(Constants.KAFKA_NAMESPACE);
    }

    KafkaDeployment deployCluster(String namespace, ManagedKafka managedKafka, String domain) throws IOException {
        // set the bootstrap server host
        managedKafka.getSpec().getEndpoint().setBootstrapServerHost(String.format("%s-kafka-bootstrap-%s.%s", managedKafka.getMetadata().getName(), namespace, domain));

        // Create cluster CA.
        cluster.kubeClient().client().secrets().inNamespace(namespace).create(new SecretBuilder()
                .editOrNewMetadata()
                .withName(String.format("%s-cluster-ca", managedKafka.getMetadata().getName()))
                .withNamespace(namespace)
                .addToLabels("strimzi.io/kind", "Kafka")
                .addToLabels("strimzi.io/cluster", managedKafka.getMetadata().getName())
                .endMetadata()
                .addToStringData("ca.key", Files.readString(new File("src/test/resources/cert/cakey.pem").toPath()))
                .build());
        cluster.kubeClient().client().secrets().inNamespace(namespace).create(new SecretBuilder()
                .editOrNewMetadata()
                .withName(String.format("%s-cluster-ca-cert", managedKafka.getMetadata().getName()))
                .withNamespace(namespace)
                .addToLabels("strimzi.io/kind", "Kafka")
                .addToLabels("strimzi.io/cluster", managedKafka.getMetadata().getName())
                .endMetadata()
                .addToStringData("ca.crt", Files.readString(new File("src/test/resources/cert/ca.pem").toPath()))
                .build());

        var configMapClient = cluster.kubeClient().client().configMaps().inNamespace(namespace);

        // set kafka and zookeeper metrics
        if (Files.exists(Environment.MONITORING_STUFF_DIR)) {
            ConfigMap kafkaMetrics = configMapClient.load(ClusterKafkaProvisioner.class.getClassLoader().getResource("kafka-metrics.yaml")).get();
            kafkaMetrics.getMetadata().setName(managedKafka.getMetadata().getName() + "-kafka-metrics");
            configMapClient.createOrReplace(kafkaMetrics);

            ConfigMap zookeeperMetrics = configMapClient.load(ClusterKafkaProvisioner.class.getClassLoader().getResource("zookeeper-metrics.yaml")).get();
            zookeeperMetrics.getMetadata().setName(managedKafka.getMetadata().getName() + "-zookeeper-metrics");
            configMapClient.createOrReplace(zookeeperMetrics);
        }

        var managedKakfaClient = cluster.kubeClient().client().customResources(ManagedKafka.class);

        managedKafka = managedKakfaClient.inNamespace(namespace).createOrReplace(managedKafka);

        var kafkaClient = cluster.kubeClient().client().customResources(Kafka.class).inNamespace(namespace).withName(managedKafka.getMetadata().getName());

        org.bf2.test.TestUtils.waitFor("kafka resource", 1_000, 300_000, () -> kafkaClient.get() != null);

        return new KafkaDeployment(managedKafka, kafkaClient.require(), cluster);
    }

    static List<RouterConfig> createIngressControllers(KubeClusterResource cluster, int numIngressControllers) throws IOException {
        var client = cluster.kubeClient().client().adapt(OpenShiftClient.class).operator().ingressControllers();
        String defaultDomain = client.inNamespace(Constants.OPENSHIFT_INGRESS_OPERATOR).withName("default").get().getStatus().getDomain();

        List<RouterConfig> routerConfigs = new ArrayList<>();
        for (int i = 0; i < numIngressControllers; i++) {
            String name = String.format("mk%s", i);
            String domain = defaultDomain.replace("apps", name);
            Map<String, String> routeSelectorLabel = Collections.singletonMap("ingressName", name);
            IngressController ingressController = new IngressControllerBuilder()
                .editOrNewMetadata()
                .withName(name)
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

            routerConfigs.add(new RouterConfig(domain, routeSelectorLabel));
        }
        return routerConfigs;
    }

    static void deleteIngressController(KubeClusterResource cluster) {
        cluster.kubeClient().client().adapt(OpenShiftClient.class).operator().ingressControllers().withName("sharded").delete();
    }

}
