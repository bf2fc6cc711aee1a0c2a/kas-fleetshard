package org.bf2.performance;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacity;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCapacityBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;

/**
 * Utils class which stores our kafka/zookeeper cluster configurations
 */
public class KafkaConfigurations {
    // TODO: not handled directly by the operator
    // private static final Probe KAFKA_LIVENESS_PROBE = new ProbeBuilder().withFailureThreshold(10).withInitialDelaySeconds(30).build();
    // private static final Probe ZOOKEEPER_LIVENESS_PROBE = new ProbeBuilder().withFailureThreshold(10).withInitialDelaySeconds(30).build();

    public static ManagedKafkaCapacity defaultCapacity(long ingressEgressThroughput) {
        ManagedKafkaCapacityBuilder builder = new ManagedKafkaCapacityBuilder();
        builder.withIngressEgressThroughputPerSec(Quantity.parse(String.valueOf(ingressEgressThroughput)));
        // TODO: this value is roughly 3x the old value from KafkaConfigurations
        // should probably default to Value Prod instead
        builder.withMaxDataRetentionSize(Quantity.parse("600Gi"));
        return builder.build();
    }

    public static ManagedKafkaBuilder apply(ManagedKafkaCapacity capacityConfig, String name) {
        ManagedKafkaBuilder builder = new ManagedKafkaBuilder();
        builder.withMetadata(new ObjectMetaBuilder().withName(name).build());
        builder.withSpec(new ManagedKafkaSpecBuilder()
                .withCapacity(capacityConfig)
                .withNewEndpoint().endEndpoint()
                // TODO: these need externalized
                .withNewVersions().withKafka("2.7.0").withStrimzi("strimzi-cluster-operator.v0.22.1-5").endVersions()
                .build());
        builder.getSpec().setCapacity(capacityConfig);
        if (Environment.APPLY_BROKER_QUOTA) {
            /* TODO: there's an open JIRA about hard / soft
            if (quota.getStorageQuotaSoftBytes() != null && quota.getStorageQuotaSoftBytes() > 0) {
                builder = builder.editSpec().editKafka().addToConfig("client.quota.callback.static.storage.soft", String.valueOf(quota.getStorageQuotaSoftBytes())).endKafka().endSpec();
            }

            if (quota.getStorageQuotaHardBytes() != null && quota.getStorageQuotaHardBytes() > 0) {
                builder = builder.editSpec().editKafka().addToConfig("client.quota.callback.static.storage.hard", String.valueOf(quota.getStorageQuotaHardBytes())).endKafka().endSpec();
            }
            */
        } else {
            // TODO: there would need to be an operator flag to disable quota settings
            // that would need to be set on the configmap used to configure the operator
        }
        return builder;
    }

    // TODO: validate that nothing else is needed in the operator that was in the other logic

}
