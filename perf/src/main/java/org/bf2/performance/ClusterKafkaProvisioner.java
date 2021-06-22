package org.bf2.performance;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.performance.k8s.KubeClusterResource;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Abstraction for managing strimzi/kafka
 */
public class ClusterKafkaProvisioner extends AbstractKafkaProvisioner {
    private static final Logger LOGGER = LogManager.getLogger(ClusterKafkaProvisioner.class);
    private final List<ManagedKafka> clusters = new ArrayList<>();
    private PerformanceStrimziOperatorManager strimziOperatorManager = new PerformanceStrimziOperatorManager();

    ClusterKafkaProvisioner(KubeClusterResource cluster) {
        super(cluster);
    }

    @Override
    public void install() throws Exception {
        if (Environment.ENABLE_DRAIN_CLEANER) {
            DrainCleanerInstaller.install(cluster, Constants.DRAIN_CLEANER_NAMESPACE);
        }

        // delete/create the namespaces to be used
        cluster.waitForDeleteNamespace(StrimziOperatorManager.OPERATOR_NS);
        cluster.waitForDeleteNamespace(FleetShardOperatorManager.OPERATOR_NS);
        cluster.createNamespace(Constants.KAFKA_NAMESPACE, Map.of(), Map.of());

        // TODO: I'm not looking at the returned futures - it's assumed that we'll eventually wait on the managed kafka deployment
        strimziOperatorManager.installStrimzi(cluster.kubeClient());
        Monitoring.connectNamespaceToMonitoringStack(cluster.kubeClient(), StrimziOperatorManager.OPERATOR_NS);

        // installs a cluster wide fleetshard operator
        FleetShardOperatorManager.deployFleetShardOperator(cluster.kubeClient());
        Monitoring.connectNamespaceToMonitoringStack(cluster.kubeClient(), FleetShardOperatorManager.OPERATOR_NS);
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
        // TODO: bounce the operator - or move the profile to install

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
        FleetShardOperatorManager.deleteFleetShard(cluster.kubeClient());
        LOGGER.info("Deleting namespace {}", Constants.KAFKA_NAMESPACE);
        cluster.waitForDeleteNamespace(Constants.KAFKA_NAMESPACE);
        strimziOperatorManager.uninstallStrimziClusterWideResources(cluster.kubeClient());
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
