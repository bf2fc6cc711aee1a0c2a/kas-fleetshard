package io.kafka.performance;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.openshift.api.model.ClusterRoleBinding;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerBuilder;
import io.kafka.performance.k8s.KubeClusterResource;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Helper class for all things installing and deploying Kafka
 */
public class KafkaInstaller {
    private static final Logger LOGGER = LogManager.getLogger(KafkaInstaller.class);
    private static final Map<String, List<Consumer<Void>>> CLUSTER_WIDE_RESOURCE_DELETERS = new ConcurrentHashMap<>();
    private static final String STRIMZI_URL_FORMAT = "https://github.com/strimzi/strimzi-kafka-operator/releases/download/%1$s/strimzi-cluster-operator-%1$s.yaml";

    static void installStrimzi(KubeClusterResource cluster, String namespace) throws Exception {
        LOGGER.info("Installing Strimzi in namespace {}", namespace);
        Map<String, String> nsAnnotations = new HashMap<>();
        if (Environment.KAFKA_COLLECT_LOG) {
            nsAnnotations.put(Constants.IO_KAFKA_PERFORMANCE_COLLECTPODLOG, "true");
        }
        cluster.kubeClient().createNamespace(namespace,
                nsAnnotations,
                Map.of("openshift.io/cluster-monitoring", "true", "app", "kafka"));

        URL url = new URL(String.format(STRIMZI_URL_FORMAT, Environment.STRIMZI_VERSION));
        LOGGER.debug("Downloading cluster operator from {}", url.toString());
        List<HasMetadata> opItems = cluster.kubeClient().getClient().load(url.openStream()).get();

        Optional<Deployment> operatorDeployment = opItems.stream().filter(h -> "strimzi-cluster-operator".equals(h.getMetadata().getName()) && h.getKind().equals("Deployment")).map(Deployment.class::cast).findFirst();
        if (operatorDeployment.isPresent()) {
            Container container = operatorDeployment.get().getSpec().getTemplate().getSpec().getContainers().get(0);
            Map<String, Quantity> limits = container.getResources().getLimits();
            limits.put("memory", Quantity.parse("1536Mi"));
            limits.put("cpu", Quantity.parse("3000m"));
            List<EnvVar> env = new ArrayList<>(container.getEnv() == null ? Collections.emptyList() : container.getEnv());
            env.add(new EnvVarBuilder().withName("STRIMZI_IMAGE_PULL_POLICY").withValue("IfNotPresent").build());
            container.setEnv(env);
        }
        opItems.stream().filter(ClusterRoleBinding.class::isInstance).forEach(cwr -> {
            cwr.getMetadata().setName(cwr.getMetadata().getName() + "." + namespace);
            CLUSTER_WIDE_RESOURCE_DELETERS.putIfAbsent(namespace, new CopyOnWriteArrayList<>());
            CLUSTER_WIDE_RESOURCE_DELETERS.get(namespace).add(unused -> {
                if (cwr instanceof ClusterRoleBinding) {
                    cluster.kubeClient().getClient().rbac().clusterRoleBindings().withName(cwr.getMetadata().getName()).delete();
                } else {
                    throw new IllegalStateException("Don't know how to delete a : " + cwr.getClass());
                }
            });
        });

        opItems.forEach(i -> {
            i.getMetadata().setNamespace(namespace);
            cluster.kubeClient().getClient().resource(i).inNamespace(namespace).createOrReplace();
        });
        LOGGER.info("Done installing Strimzi in namespace {}", namespace);

        Monitoring.connectNamespaceToMonitoringStack(cluster, namespace);
    }

    static KafkaDeployment deployCluster(KubeClusterResource cluster, String namespace, ManagedKafka managedKafka, String domain) throws IOException {
        // set the bootstrap server host
        managedKafka.getSpec().getEndpoint().setBootstrapServerHost(String.format("%s-kafka-bootstrap-%s.%s", managedKafka.getMetadata().getName(), namespace, domain));

        // Create cluster CA.
        // TODO: is this needed?
        cluster.kubeClient().namespace(namespace).createSecret(new SecretBuilder()
                .editOrNewMetadata()
                .withName(String.format("%s-cluster-ca", managedKafka.getMetadata().getName()))
                .withNamespace(namespace)
                .addToLabels("strimzi.io/kind", "Kafka")
                .addToLabels("strimzi.io/cluster", managedKafka.getMetadata().getName())
                .endMetadata()
                .addToStringData("ca.key", Files.readString(new File("src/test/resources/cert/cakey.pem").toPath()))
                .build());
        cluster.kubeClient().namespace(namespace).createSecret(new SecretBuilder()
                .editOrNewMetadata()
                .withName(String.format("%s-cluster-ca-cert", managedKafka.getMetadata().getName()))
                .withNamespace(namespace)
                .addToLabels("strimzi.io/kind", "Kafka")
                .addToLabels("strimzi.io/cluster", managedKafka.getMetadata().getName())
                .endMetadata()
                .addToStringData("ca.crt", Files.readString(new File("src/test/resources/cert/ca.pem").toPath()))
                .build());

        var configMapClient = cluster.kubeClient().getClient().configMaps().inNamespace(namespace);

        // set kafka and zookeeper metrics
        if (Files.exists(Environment.MONITORING_STUFF_DIR)) {
            ConfigMap kafkaMetrics = configMapClient.load(KafkaInstaller.class.getClassLoader().getResource("kafka-metrics.yaml")).get();
            kafkaMetrics.getMetadata().setName(managedKafka.getMetadata().getName() + "-kafka-metrics");
            configMapClient.createOrReplace(kafkaMetrics);

            ConfigMap zookeeperMetrics = configMapClient.load(KafkaInstaller.class.getClassLoader().getResource("zookeeper-metrics.yaml")).get();
            zookeeperMetrics.getMetadata().setName(managedKafka.getMetadata().getName() + "-zookeeper-metrics");
            configMapClient.createOrReplace(zookeeperMetrics);

            // set by the operator
            // kafka.getSpec().setKafkaExporter(new KafkaExporterSpecBuilder()
               // .withGroupRegex(".*")
               // .withTopicRegex(".*")
               // .build());
        }

        // handled by the operator
        /* List<GenericKafkaListener> listeners = kafka.getSpec().getKafka().getListeners().getGenericKafkaListeners();
        if (listeners != null) {
            listeners.forEach(l -> {
                if (KafkaListenerType.ROUTE.equals(l.getType())) {
                    GenericKafkaListenerConfigurationBuilder genericKafkaListenerConfigurationBuilder = new GenericKafkaListenerConfigurationBuilder()
                            .withBootstrap(new GenericKafkaListenerConfigurationBootstrapBuilder()
                                    .withHost(String.format("%s-kafka-bootstrap-%s.%s", kafka.getMetadata().getName(), kafka.getMetadata().getNamespace(), domain))
                                    .build());

                    for (int i = 0; i < kafka.getSpec().getKafka().getReplicas(); i++) {
                        genericKafkaListenerConfigurationBuilder.addNewBroker()
                                .withBroker(i)
                                .withHost(String.format("%s-kafka-%d-%s.%s", kafka.getMetadata().getName(), i, kafka.getMetadata().getNamespace(), domain))
                                .endBroker();
                    }
                    l.setConfiguration(genericKafkaListenerConfigurationBuilder.build());
                }
            });
        }*/

        var managedKakfaClient = cluster.kubeClient().getClient().customResources(ManagedKafka.class);

        managedKafka = managedKakfaClient.inNamespace(namespace).createOrReplace(managedKafka);

        var kafkaClient = cluster.kubeClient().getClient().customResources(Kafka.class).inNamespace(namespace).withName(managedKafka.getMetadata().getName());

        try {
            kafkaClient.waitUntilCondition(Objects::nonNull, 5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        return new KafkaDeployment(managedKafka, kafkaClient.require(), cluster);
    }

    static void uninstallStrimziClusterWideResources(String name) {
        LOGGER.info("Uninstalling Strimzi cluster wide resources");
        List<Consumer<Void>> remove = CLUSTER_WIDE_RESOURCE_DELETERS.remove(name);
        if (remove != null) {
            remove.forEach(delete -> delete.accept(null));
        }
    }

    static String createIngressController(KubeClusterResource cluster) throws IOException {
        var client = cluster.kubeClient().getClient().operator().ingressControllers();

        IngressController ingressController = client.withName("default").get();

        boolean create = true;
        String domain = "";
        if (ingressController != null && ingressController.getStatus() != null) {
            domain = ingressController.getStatus().getDomain().trim().replace("apps.", "mk.");
            if (ingressController.getStatus().getDomain().trim().contains("no value")) {
                create = false;
                domain = cluster.kubeClient().getClient().getMasterUrl().getHost().replaceFirst("api", "apps");
            }
        }
        if (create) {
            Map<String, String> parameters = Map.of("DOMAIN", domain, "REPLICAS", cluster.isMultiAZ() ? "3" : "2");
            cluster.cmdKubeClient().process(parameters, Constants.SUITE_ROOT + "/src/test/resources/managed-kafka-support/ingresscontroller.yaml", s -> cluster.cmdKubeClient(Constants.OPENSHIFT_INGRESS_OPERATOR).applyContent(s));
        }

        return domain;
    }

    static List<RouterConfig> createIngressControllers(KubeClusterResource cluster, int numIngressControllers) throws IOException {
        var client = cluster.kubeClient().getClient().operator().ingressControllers();
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
        cluster.kubeClient().getClient().operator().ingressControllers().withName("sharded").delete();
    }
}
