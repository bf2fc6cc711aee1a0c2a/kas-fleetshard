package org.bf2.performance;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.performance.k8s.KubeClusterResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Helper class for all things installing and deploying Kafka
 */
public class KafkaInstaller {

    static KafkaDeployment deployCluster(KubeClusterResource cluster, String namespace, ManagedKafka managedKafka, String domain) throws IOException {
        // set the bootstrap server host
        managedKafka.getSpec().getEndpoint().setBootstrapServerHost(String.format("%s-kafka-bootstrap-%s.%s", managedKafka.getMetadata().getName(), namespace, domain));

        // Create cluster CA.
        // TODO: is this needed?
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

        var managedKakfaClient = cluster.kubeClient().client().customResources(ManagedKafka.class);

        managedKafka = managedKakfaClient.inNamespace(namespace).createOrReplace(managedKafka);

        var kafkaClient = cluster.kubeClient().client().customResources(Kafka.class).inNamespace(namespace).withName(managedKafka.getMetadata().getName());

        try {
            kafkaClient.waitUntilCondition(Objects::nonNull, 5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        return new KafkaDeployment(managedKafka, kafkaClient.require(), cluster);
    }

    static String createIngressController(KubeClusterResource cluster) throws IOException {
        var client = cluster.kubeClient().client().adapt(OpenShiftClient.class).operator().ingressControllers();

        IngressController ingressController = client.withName("default").get();

        boolean create = true;
        String domain = "";
        if (ingressController != null && ingressController.getStatus() != null) {
            domain = ingressController.getStatus().getDomain().trim().replace("apps.", "mk.");
            if (ingressController.getStatus().getDomain().trim().contains("no value")) {
                create = false;
                domain = cluster.kubeClient().client().getMasterUrl().getHost().replaceFirst("api", "apps");
            }
        }
        if (create) {
            Map<String, String> parameters = Map.of("DOMAIN", domain, "REPLICAS", cluster.isMultiAZ() ? "3" : "2");
            cluster.cmdKubeClient().process(parameters, Constants.SUITE_ROOT + "/src/test/resources/managed-kafka-support/ingresscontroller.yaml", s -> cluster.cmdKubeClient(Constants.OPENSHIFT_INGRESS_OPERATOR).applyContent(s));
        }

        return domain;
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
