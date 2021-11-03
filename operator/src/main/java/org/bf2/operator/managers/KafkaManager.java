package org.bf2.operator.managers;

import io.quarkus.runtime.Startup;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@Startup
@ApplicationScoped
public class KafkaManager {

    @Inject
    Logger log;

    @Inject
    protected InformerManager informerManager;

    @Inject
    protected StrimziManager strimziManager;

    public String getKafkaVersion(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster) {
        boolean kafkaVersionChanged = this.hasKafkaVersionChanged(managedKafka);
        // in the same reconcile loop Strimzi version could have been changed but updating not started yet
        boolean isStrimziUpdating = kafkaCluster.isStrimziUpdating(managedKafka) || this.strimziManager.hasStrimziChanged(managedKafka);
        log.infof("kafkaVersionChanged = %s, isStrimziUpdating = %s", kafkaVersionChanged, isStrimziUpdating);
        if (kafkaVersionChanged && !isStrimziUpdating) {
            log.infof("Kafka change from %s to %s",
                    this.currentKafkaVersion(managedKafka), managedKafka.getSpec().getVersions().getKafka());
            return managedKafka.getSpec().getVersions().getKafka();
        } else {
            log.infof("Stay with current Kafka version");
            return this.currentKafkaVersion(managedKafka);
        }
    }

    public String getKafkaIbpVersion(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster) {
        boolean kafkaIbpVersionChanged = this.hasKafkaIbpVersionChanged(managedKafka);
        // in the same reconcile loop Strimzi version could have been changed but updating not started yet
        boolean isStrimziUpdating = kafkaCluster.isStrimziUpdating(managedKafka) || this.strimziManager.hasStrimziChanged(managedKafka);
        // in the same reconcile loop Kafka version could have been changed but updating not started yet
        boolean isKafkaUpdating = kafkaCluster.isKafkaUpdating(managedKafka) || this.hasKafkaVersionChanged(managedKafka);
        log.infof("kafkaIbpVersionChanged = %s, isStrimziUpdating = %s, isKafkaUpdating = %s", kafkaIbpVersionChanged, isStrimziUpdating, isKafkaUpdating);
        if (kafkaIbpVersionChanged && !isStrimziUpdating && !isKafkaUpdating) {
            String kafkaIbpVersion = managedKafka.getSpec().getVersions().getKafkaIbp();
            // dealing with ManagedKafka instances not having the IBP field specified
            if (kafkaIbpVersion == null) {
                kafkaIbpVersion = AbstractKafkaCluster.getKafkaIbpVersion(managedKafka.getSpec().getVersions().getKafka());
            }
            log.infof("Kafka IBP change from %s to %s",
                    this.currentKafkaIbpVersion(managedKafka), kafkaIbpVersion);
            return kafkaIbpVersion;
        } else {
            log.infof("Stay with current Kafka IBP version");
            return this.currentKafkaIbpVersion(managedKafka);
        }
    }

    public String getKafkaLogMessageFormatVersion(ManagedKafka managedKafka) {
        return this.currentKafkaLogMessageFormatVersion(managedKafka);
    }

    /**
     * Compare current Kafka version from the Kafka custom resource with the requested one in the ManagedKafka spec
     * in order to return if a version change happened
     *
     * @param managedKafka ManagedKafka instance
     * @return if a Kafka version change was requested
     */
    public boolean hasKafkaVersionChanged(ManagedKafka managedKafka) {
        log.infof("requestedKafkaVersion = %s", managedKafka.getSpec().getVersions().getKafka());
        return !this.currentKafkaVersion(managedKafka).equals(managedKafka.getSpec().getVersions().getKafka());
    }

    /**
     * Returns the current Kafka version for the Kafka instance
     * It comes directly from the Kafka custom resource or from the ManagedKafka in case of creation
     *
     * @param managedKafka ManagedKafka instance
     * @return current Kafka version for the Kafka instance
     */
    private String currentKafkaVersion(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        // on first time Kafka resource creation, we take the Kafka version from the ManagedKafka resource spec
        String kafkaVersion = kafka != null ?
                kafka.getSpec().getKafka().getVersion() :
                managedKafka.getSpec().getVersions().getKafka();
        log.infof("currentKafkaVersion = %s", kafkaVersion);
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
        log.infof("requestedKafkaIbpVersion = %s", kafkaIbpVersion);
        return !this.currentKafkaIbpVersion(managedKafka).equals(kafkaIbpVersion);
    }

    /**
     * Returns the current Kafka inter broker protocol version for the Kafka instance
     * It comes directly from the Kafka custom resource or from the ManagedKafka in case of creation
     *
     * @param managedKafka ManagedKafka instance
     * @return current Kafka inter broker protocol version for the Kafka instance
     */
    private String currentKafkaIbpVersion(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        String kafkaIbpVersion ;
        // on first time Kafka resource creation, we take the Kafka inter broker protocol version from the ManagedKafka resource spec
        if (kafka != null) {
            Object interBrokerProtocol = kafka.getSpec().getKafka().getConfig().get("inter.broker.protocol.version");
            kafkaIbpVersion = interBrokerProtocol != null ? interBrokerProtocol.toString() : AbstractKafkaCluster.getKafkaIbpVersion(kafka.getSpec().getKafka().getVersion());
        } else {
            kafkaIbpVersion = managedKafka.getSpec().getVersions().getKafkaIbp();
            // dealing with ManagedKafka instances not having the IBP field specified
            if (kafkaIbpVersion == null) {
                kafkaIbpVersion = AbstractKafkaCluster.getKafkaIbpVersion(managedKafka.getSpec().getVersions().getKafka());
            }
        }
        log.infof("currentKafkaIbpVersion = %s", kafkaIbpVersion);
        return kafkaIbpVersion;
    }

    /**
     * Returns the current Kafka log message format version for the Kafka instance
     * It comes directly from the Kafka custom resource or from the ManagedKafka in case of creation
     *
     * @param managedKafka ManagedKafka instance
     * @return current Kafka log message format version for the Kafka instance
     */
    private String currentKafkaLogMessageFormatVersion(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        String kafkaLogMessageFormatVersion;
        // on first time Kafka resource creation, we take the Kafka log message format version from the ManagedKafka resource spec
        if (kafka != null) {
            Object logMessageFormat = kafka.getSpec().getKafka().getConfig().get("log.message.format.version");
            kafkaLogMessageFormatVersion = logMessageFormat != null ? logMessageFormat.toString() : AbstractKafkaCluster.getKafkaLogMessageFormatVersion(kafka.getSpec().getKafka().getVersion());
        } else {
            kafkaLogMessageFormatVersion = AbstractKafkaCluster.getKafkaLogMessageFormatVersion(managedKafka.getSpec().getVersions().getKafka());
        }
        log.infof("currentKafkaLogMessageFormatVersion = %s", kafkaLogMessageFormatVersion);
        return kafkaLogMessageFormatVersion;
    }

    private Kafka cachedKafka(ManagedKafka managedKafka) {
        return this.informerManager.getLocalKafka(AbstractKafkaCluster.kafkaClusterNamespace(managedKafka), AbstractKafkaCluster.kafkaClusterName(managedKafka));
    }
}
