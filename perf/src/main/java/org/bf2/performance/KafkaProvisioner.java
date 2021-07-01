package org.bf2.performance;

import org.bf2.operator.operands.KafkaInstanceConfiguration;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.performance.framework.KubeClusterResource;
import org.bf2.performance.framework.TestMetadataCapture;

import java.io.IOException;

/**
 * Abstraction for managing strimzi and kafka.
 */
public interface KafkaProvisioner {
    /**
     * One-time setup of provisioner. This should be called only once per test class.
     */
    void setup() throws Exception;

    /**
     * One-time teardown of provisioner. This should be called only once per test class.
     */
    void teardown() throws Exception;

    /**
     * Install this Kafka provisioner. This can be called once per test class or per test method.
     */
    void install() throws Exception;

    /**
     * Deploy a Kafka cluster using this provisioner.
     * @param profile
     */
    KafkaDeployment deployCluster(ManagedKafka kafka, KafkaInstanceConfiguration profile) throws Exception;

    /**
     * Uninstall this provisioner from the system. This  will also delete all Kafka clusters created by
     * the provisioner. This can be called once per test class or per test method.
     */
    void uninstall() throws Exception;

    /**
     * Get the kubernetes cluster handle for this provisioner.
     */
    KubeClusterResource getKubernetesCluster();

    /**
     * Create a kafka provisioner for a given cluster.
     */
    static KafkaProvisioner create(KubeClusterResource cluster) throws IOException {
        TestMetadataCapture.getInstance().storeKafkaOpenshiftData(cluster);
        return new ClusterKafkaProvisioner(cluster);
    }
}
