package org.bf2.performance;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.performance.k8s.KubeClusterResource;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/**
 * Abstraction for managing strimzi/kafka
 */
public class ClusterKafkaProvisioner extends AbstractKafkaProvisioner {
    private static final Logger LOGGER = LogManager.getLogger(ClusterKafkaProvisioner.class);
    private final List<ManagedKafka> clusters = new ArrayList<>();

    ClusterKafkaProvisioner(KubeClusterResource cluster) {
        super(cluster);
    }

    @Override
    public void install() throws Exception {
        if (Environment.ENABLE_DRAIN_CLEANER) {
            DrainCleanerInstaller.install(cluster, Constants.DRAIN_CLEANER_NAMESPACE);
        }
        KafkaInstaller.installStrimzi(cluster, Constants.KAFKA_NAMESPACE);
        // TODO: install operator
    }

    @Override
    public KafkaDeployment deployCluster(ManagedKafka managedKafka, AdopterProfile profile) throws Exception {
        int clusterNumber = 1;
        RouterConfig routerConfig = routerConfigs.get(clusterNumber % routerConfigs.size());

        clusters.add(managedKafka);

        // TODO: should this be a fixed namespace
        String namespace = Constants.KAFKA_NAMESPACE;

        managedKafka.getMetadata().setNamespace(namespace);
        // TODO: the operator is not doing this, just setting ingressType to sharded
        // kafka.getMetadata().getLabels().putAll(routerConfig.getRouteSelectorLabels());

        // TODO: drain cleaner is not supported
        if (Environment.ENABLE_DRAIN_CLEANER) {
            // this settings blocks K8s draining as it is done by the DrainCleaner application
            //kafka.getSpec().getKafka().getTemplate().setPodDisruptionBudget(new PodDisruptionBudgetTemplateBuilder().withMaxUnavailable(0).build());
            //kafka.getSpec().getZookeeper().getTemplate().setPodDisruptionBudget(new PodDisruptionBudgetTemplateBuilder().withMaxUnavailable(0).build());
        }

        LOGGER.info("Cluster {} deploy with domain {}", managedKafka.getMetadata().getName(), routerConfig.getDomain());

        // convert the profile into simple configmap values - the operator should restart with these values
        ConfigMap override = toConfigMap(profile);
        cluster.kubeClient().client().configMaps().inNamespace(namespace).createOrReplace(override);
        // TODO: bounce or otherwise confirm the operator has restarted

        KafkaDeployment kafkaDeployment = KafkaInstaller.deployCluster(cluster, namespace, managedKafka, routerConfig.getDomain());
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
            awaitKafkaDeploymentRemoval(client, k);
            kafkaIterator.remove();
        }
    }

    @Override
    public void uninstall() throws Exception {
        removeClusters();
        LOGGER.info("Deleting namespace {}", Constants.KAFKA_NAMESPACE);
        cluster.waitForDeleteNamespace(Constants.KAFKA_NAMESPACE);
        KafkaInstaller.uninstallStrimziClusterWideResources(Constants.KAFKA_NAMESPACE);
        if (Environment.ENABLE_DRAIN_CLEANER) {
            LOGGER.info("Deleting namespace {}", Constants.DRAIN_CLEANER_NAMESPACE);
            cluster.waitForDeleteNamespace(Constants.DRAIN_CLEANER_NAMESPACE);
        }
    }

    @Override
    public KubeClusterResource getKubernetesCluster() {
        return cluster;
    }

}
