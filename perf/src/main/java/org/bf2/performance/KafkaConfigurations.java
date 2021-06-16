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
                .withNewVersions().withKafka("2.7.0").withStrimzi("0.22.1").endVersions()
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

        /*
        return new KafkaBuilder()
                .editOrNewMetadata()
                .addToLabels("ingressType", "sharded")
                .endMetadata()
                .editOrNewSpec()
                .editOrNewClusterCa()
                .withGenerateCertificateAuthority(false)
                .withCertificateExpirationPolicy(null)
                .withRenewalDays(30)
                .withValidityDays(365)
                .endClusterCa()
                .editOrNewKafka()
                .withImage(Constants.KAFKA_IMAGE)
                .withReplicas(3)
                .editOrNewJvmOptions()
                .withXms(toJavaMemory(kafkaJavaMem))
                .withXmx(toJavaMemory(kafkaJavaMem))
                .endJvmOptions()
                .editOrNewTemplate()
                .editOrNewPod()
                .withAffinity(buildAffinity())
                .endPod()
                .endTemplate()
                .withResources(kafka)
                .addToConfig("offsets.topic.replication.factor", 3)
                .addToConfig("retention.ms", 300000)
                .addToConfig("segment.bytes", 10737418240L)
                .addToConfig("transaction.state.log.min.isr", 2)
                .addToConfig("transaction.state.log.replication.factor", 3)
                .withNewJbodStorage()
                .withVolumes(buildStorage("200Gi"))
                .endJbodStorage()
                .withRack(new RackBuilder().withNewTopologyKey("topology.kubernetes.io/zone").build())
                .editOrNewListeners()
                .addToGenericKafkaListeners(new GenericKafkaListenerBuilder()
                        .withName("external")
                        .withType(KafkaListenerType.ROUTE)
                        .withPort(9094)
                        .withTls(true)
                        .build())
                .endListeners()
                .withLivenessProbe(KAFKA_LIVENESS_PROBE)
                .endKafka()
                .editOrNewZookeeper()
                .withReplicas(3)
                .editOrNewTemplate()
                .editOrNewPod()
                .withAffinity(buildHostnameAndZoneAffinity(multiAZ))
                .endPod()
                .endTemplate()
                .editOrNewJvmOptions()
                .withXms(toJavaMemory(zkJavaMem))
                .withXmx(toJavaMemory(zkJavaMem))
                .endJvmOptions()
                .withResources(zk)
                .withNewPersistentClaimStorage()
                .withDeleteClaim(true)
                .withStorageClass(Constants.MK_STORAGECLASS)
                .withSize("60Gi")
                .endPersistentClaimStorage()
                .withLivenessProbe(ZOOKEEPER_LIVENESS_PROBE)
                .endZookeeper()
                .endSpec();
    */

    /* all handled by the operator
    private static PersistentClaimStorage buildStorage(String size) {
        return new PersistentClaimStorageBuilder()
                .withId(0)
                .withSize(size)
                .withDeleteClaim(true)
                .withNewStorageClass(Constants.MK_STORAGECLASS)
                .build();
    }

    private static Affinity buildAffinity() {
        return new AffinityBuilder()
                .editOrNewPodAntiAffinity()
                .addNewRequiredDuringSchedulingIgnoredDuringExecution()
                .withTopologyKey("kubernetes.io/hostname")
                .endRequiredDuringSchedulingIgnoredDuringExecution()
                .endPodAntiAffinity()
                .build();
    }

    private static Affinity buildHostnameAndZoneAffinity(boolean multiAZ) {
        AffinityFluent.PodAntiAffinityNested<AffinityBuilder> nodeAndZoneAffinityBuilder = new AffinityBuilder().editOrNewPodAntiAffinity()
                .addNewRequiredDuringSchedulingIgnoredDuringExecution()
                .withTopologyKey("kubernetes.io/hostname")
                .endRequiredDuringSchedulingIgnoredDuringExecution();

        if (multiAZ) {
            nodeAndZoneAffinityBuilder
                    .addNewRequiredDuringSchedulingIgnoredDuringExecution()
                    .withTopologyKey("topology.kubernetes.io/zone")
                    .endRequiredDuringSchedulingIgnoredDuringExecution();
        }

        return nodeAndZoneAffinityBuilder.endPodAntiAffinity().build();
    }*/

}
