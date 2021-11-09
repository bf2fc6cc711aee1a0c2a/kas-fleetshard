package org.bf2.operator.managers;

import io.quarkus.runtime.Startup;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Map;

@Startup
@ApplicationScoped
public class KafkaManager {

    @Inject
    Logger log;

    @Inject
    protected InformerManager informerManager;

    /**
     * Upgrade the Kafka version to use on the Kafka instance by taking it from the ManagedKafka resource
     *
     * @param managedKafka ManagedKafka instance to get the Kafka version
     * @param kafkaBuilder KafkaBuilder instance to update the Kafka version on the cluster
     */
    public void upgradeKafkaVersion(ManagedKafka managedKafka, KafkaBuilder kafkaBuilder) {
        log.infof("Kafka change from %s to %s",
                kafkaBuilder.buildSpec().getKafka().getVersion(), managedKafka.getSpec().getVersions().getKafka());
        kafkaBuilder
                .editSpec()
                    .editKafka()
                        .withVersion(managedKafka.getSpec().getVersions().getKafka())
                    .endKafka()
                .endSpec();
    }

    /**
     * Upgrade the Kafka inter broker protocol version to use on the Kafka instance by taking it from the ManagedKafka resource
     *
     * @param managedKafka ManagedKafka instance to get the Kafka version
     * @param kafkaBuilder KafkaBuilder instance to update the Kafka inter broker protocol version on the cluster
     */
    public void upgradeKafkaIbpVersion(ManagedKafka managedKafka, KafkaBuilder kafkaBuilder) {
        String kafkaIbpVersion = managedKafka.getSpec().getVersions().getKafkaIbp();
        // dealing with ManagedKafka instances not having the IBP field specified
        if (kafkaIbpVersion == null) {
            kafkaIbpVersion = AbstractKafkaCluster.getKafkaIbpVersion(managedKafka.getSpec().getVersions().getKafka());
        }
        Map<String, Object> config = kafkaBuilder
                .buildSpec()
                .getKafka()
                .getConfig();
        log.infof("Kafka IBP change from %s to %s", config.get("inter.broker.protocol.version"), kafkaIbpVersion);
        config.put("inter.broker.protocol.version", kafkaIbpVersion);
        kafkaBuilder
                .editSpec()
                    .editKafka()
                        .withConfig(config)
                    .endKafka()
                .endSpec();
    }

    /**
     * Compare current Kafka version from the Kafka custom resource with the requested one in the ManagedKafka spec
     * in order to return if a version change happened
     *
     * @param managedKafka ManagedKafka instance
     * @return if a Kafka version change was requested
     */
    public boolean hasKafkaVersionChanged(ManagedKafka managedKafka) {
        log.debugf("requestedKafkaVersion = %s", managedKafka.getSpec().getVersions().getKafka());
        return !this.currentKafkaVersion(managedKafka).equals(managedKafka.getSpec().getVersions().getKafka());
    }

    /**
     * Returns the current Kafka version for the Kafka instance
     * It comes directly from the Kafka custom resource or from the ManagedKafka in case of creation
     *
     * @param managedKafka ManagedKafka instance
     * @return current Kafka version for the Kafka instance
     */
    public String currentKafkaVersion(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        // on first time Kafka resource creation, we take the Kafka version from the ManagedKafka resource spec
        String kafkaVersion = kafka != null ?
                kafka.getSpec().getKafka().getVersion() :
                managedKafka.getSpec().getVersions().getKafka();
        log.debugf("currentKafkaVersion = %s", kafkaVersion);
        return kafkaVersion;
    }

    /**
     * Compare current Kafka inter broker protocol version from the Kafka custom resource with the requested one in the ManagedKafka spec
     * in order to return if a version change happened
     *
     * @param managedKafka ManagedKafka instance
     * @return if a Kafka inter broker protocol version change was requested
     */
    public boolean hasKafkaIbpVersionChanged(ManagedKafka managedKafka) {
        String kafkaIbpVersion = managedKafka.getSpec().getVersions().getKafkaIbp();
        // dealing with ManagedKafka instances not having the IBP field specified
        if (kafkaIbpVersion == null) {
            kafkaIbpVersion = AbstractKafkaCluster.getKafkaIbpVersion(managedKafka.getSpec().getVersions().getKafka());
        }
        log.debugf("requestedKafkaIbpVersion = %s", kafkaIbpVersion);
        return !this.currentKafkaIbpVersion(managedKafka).equals(kafkaIbpVersion);
    }

    /**
     * Returns the current Kafka inter broker protocol version for the Kafka instance
     * It comes directly from the Kafka custom resource or from the ManagedKafka in case of creation
     *
     * @param managedKafka ManagedKafka instance
     * @return current Kafka inter broker protocol version for the Kafka instance
     */
    public String currentKafkaIbpVersion(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        String kafkaIbpVersion ;
        // on first time Kafka resource creation, we take the Kafka inter broker protocol version from the ManagedKafka resource spec
        if (kafka != null) {
            Object interBrokerProtocol = kafka.getSpec().getKafka().getConfig().get("inter.broker.protocol.version");
            kafkaIbpVersion = interBrokerProtocol != null ?
                    AbstractKafkaCluster.getKafkaIbpVersion(interBrokerProtocol.toString()) :
                    AbstractKafkaCluster.getKafkaIbpVersion(kafka.getSpec().getKafka().getVersion());
        } else {
            kafkaIbpVersion = managedKafka.getSpec().getVersions().getKafkaIbp();
            // dealing with ManagedKafka instances not having the IBP field specified
            if (kafkaIbpVersion == null) {
                kafkaIbpVersion = AbstractKafkaCluster.getKafkaIbpVersion(managedKafka.getSpec().getVersions().getKafka());
            }
        }
        log.debugf("currentKafkaIbpVersion = %s", kafkaIbpVersion);
        return kafkaIbpVersion;
    }

    /**
     * Returns the current Kafka log message format version for the Kafka instance
     * It comes directly from the Kafka custom resource or from the ManagedKafka in case of creation
     *
     * @param managedKafka ManagedKafka instance
     * @return current Kafka log message format version for the Kafka instance
     */
    public String currentKafkaLogMessageFormatVersion(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        String kafkaLogMessageFormatVersion;
        // on first time Kafka resource creation, we take the Kafka log message format version from the ManagedKafka resource spec
        if (kafka != null) {
            Object logMessageFormat = kafka.getSpec().getKafka().getConfig().get("log.message.format.version");
            kafkaLogMessageFormatVersion = logMessageFormat != null ? logMessageFormat.toString() : AbstractKafkaCluster.getKafkaLogMessageFormatVersion(kafka.getSpec().getKafka().getVersion());
        } else {
            kafkaLogMessageFormatVersion = AbstractKafkaCluster.getKafkaLogMessageFormatVersion(managedKafka.getSpec().getVersions().getKafka());
        }
        log.debugf("currentKafkaLogMessageFormatVersion = %s", kafkaLogMessageFormatVersion);
        return kafkaLogMessageFormatVersion;
    }

    /**
     * Returns if a Kafka version upgrade is in progress
     *
     * @param managedKafka ManagedKafka instance
     * @param kafkaCluster Kafka cluster operand
     * @return if a Kafka version upgrade is in progress
     */
    public boolean isKafkaUpgradeInProgress(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster) {
        return kafkaCluster.isKafkaUpdating(managedKafka);
    }

    /**
     * Returns if a Kafka inter broker protocol version upgrade is in progress
     *
     * @param managedKafka ManagedKafka instance
     * @param kafkaCluster Kafka cluster operand
     * @return if a Kafka inter broker protocol version upgrade is in progress
     */
    public boolean isKafkaIbpUpgradeInProgress(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster) {
        return kafkaCluster.isKafkaIbpUpdating(managedKafka);
    }

    private Kafka cachedKafka(ManagedKafka managedKafka) {
        return this.informerManager.getLocalKafka(AbstractKafkaCluster.kafkaClusterNamespace(managedKafka), AbstractKafkaCluster.kafkaClusterName(managedKafka));
    }
}
