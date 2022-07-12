package org.bf2.operator.managers;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.ManagedKafkaKeys.Annotations;
import org.bf2.operator.clients.canary.CanaryService;
import org.bf2.operator.clients.canary.Status;
import org.bf2.operator.operands.AbstractCanary;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.net.URI;
import java.sql.Date;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class KafkaManager {

    private static final long STATUS_TIME_WINDOW_PERCENTAGE_CORRECTION = 10; // 10% more

    @Inject
    Logger log;

    @Inject
    protected InformerManager informerManager;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    org.quartz.Scheduler quartz;

    @ConfigProperty(name = "managedkafka.canary.status-time-window-ms")
    Long statusTimeWindowMs;
    long checkStabilityTimeMs;

    @ConfigProperty(name = "managedkafka.upgrade.consuming-percentage-threshold")
    Integer consumingPercentageThreshold;

    @ConfigProperty(name = "managedkafka.upgrade.stability-check-enabled", defaultValue = "true")
    boolean stabilityCheckEnabled;

    private MixedOperation<ManagedKafka, ManagedKafkaList, Resource<ManagedKafka>> managedKafkaClient;

    @PostConstruct
    protected void onStart() {
        managedKafkaClient = kubernetesClient.resources(ManagedKafka.class, ManagedKafkaList.class);
        checkStabilityTimeMs = statusTimeWindowMs + (statusTimeWindowMs * STATUS_TIME_WINDOW_PERCENTAGE_CORRECTION) / 100;
    }

    /**
     * Upgrade the Kafka version to use on the Kafka instance by taking it from the ManagedKafka resource
     *
     * @param managedKafka ManagedKafka instance to get the Kafka version
     * @param kafkaBuilder KafkaBuilder instance to upgrade the Kafka version on the cluster
     */
    public void upgradeKafkaVersion(ManagedKafka managedKafka, KafkaBuilder kafkaBuilder) {
        log.infof("[%s/%s] Kafka change from %s to %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(),
                kafkaBuilder.buildSpec().getKafka().getVersion(), managedKafka.getSpec().getVersions().getKafka());
        kafkaBuilder
                .editSpec()
                    .editKafka()
                        .withVersion(managedKafka.getSpec().getVersions().getKafka())
                    .endKafka()
                .endSpec();

        // stamp the ManagedKafka resource with starting time of Kafka upgrade
        addUpgradeTimeStampAnnotation(managedKafka, Annotations.KAFKA_UPGRADE_START_TIMESTAMP);
    }

    /**
     * Upgrade the Kafka inter broker protocol version to use on the Kafka instance by taking it from the ManagedKafka resource
     *
     * @param managedKafka ManagedKafka instance to get the Kafka version
     * @param kafkaBuilder KafkaBuilder instance to upgrade the Kafka inter broker protocol version on the cluster
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
        log.infof("[%s/%s] Kafka IBP change from %s to %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(),
                config.get("inter.broker.protocol.version"), kafkaIbpVersion);
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
        log.debugf("[%s/%s] requestedKafkaVersion = %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(),
                managedKafka.getSpec().getVersions().getKafka());
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
        log.debugf("[%s/%s] currentKafkaVersion = %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), kafkaVersion);
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
        log.debugf("[%s/%s] requestedKafkaIbpVersion = %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), kafkaIbpVersion);
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
        log.debugf("[%s/%s] currentKafkaIbpVersion = %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), kafkaIbpVersion);
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
        String current;
        // on first time Kafka resource creation, we take the Kafka log message format version from the ManagedKafka resource spec
        if (kafka != null) {
            Object logMessageFormat = kafka.getSpec().getKafka().getConfig().get("log.message.format.version");
            current = logMessageFormat != null ? logMessageFormat.toString() : kafka.getSpec().getKafka().getVersion();
        } else {
            current = managedKafka.getSpec().getVersions().getKafka();
        }
        kafkaLogMessageFormatVersion = AbstractKafkaCluster.getKafkaLogMessageFormatVersion(current);
        log.debugf("[%s/%s] currentKafkaLogMessageFormatVersion = %s",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), kafkaLogMessageFormatVersion);
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

    /**
     * Returns if a Kafka stability check is in progress after a Kafka version upgrade
     *
     * @param managedKafka ManagedKafka instance
     * @param kafkaCluster Kafka cluster operand
     * @return if a Kafka stability check is in progress after a Kafka version upgrade
     */
    public boolean isKafkaUpgradeStabilityCheckInProgress(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster) {
        // taking into account that the stability check job was scheduled
        // NOTE: it's useful when operator crashes after Kafka upgrade ends but stability check not started yet.
        //       On restart, the job has to be scheduled again.
        boolean jobExists = isKafkaUpgradeStabilityCheckJobExists(managedKafka);
        return kafkaCluster.isKafkaUpgradeStabilityChecking(managedKafka) && jobExists;
    }

    /**
     * Returns if a Kafka stability check has to run/scheduled after a Kafka version upgrade
     *
     * @param managedKafka ManagedKafka instance
     * @param kafkaCluster Kafka cluster operand
     * @return if a Kafka stability check has to run/scheduled after a Kafka version upgrade
     */
    public boolean isKafkaUpgradeStabilityCheckToRun(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster) {
        Optional<String> kafkaUpgradeStartTimestampAnnotation = managedKafka.getAnnotation(Annotations.KAFKA_UPGRADE_START_TIMESTAMP);

        // taking into account that the stability check job was scheduled
        // NOTE: it's useful when operator crashes after Kafka upgrade ends but stability check not started yet.
        //       On restart, the job has to be scheduled again.
        boolean jobExists = isKafkaUpgradeStabilityCheckJobExists(managedKafka);

        return kafkaUpgradeStartTimestampAnnotation.isPresent() && !jobExists;
    }

    /**
     * Schedule/Run the Kafka stability check
     *
     * @param managedKafka ManagedKafka instance
     */
    public void checkKafkaUpgradeIsStable(ManagedKafka managedKafka) {
        Optional<String> kafkaUpgradeStartTimestampAnnotation = managedKafka.getAnnotation(Annotations.KAFKA_UPGRADE_START_TIMESTAMP);

        // the stability check starts only if a Kafka upgrade was in place
        if (kafkaUpgradeStartTimestampAnnotation.isPresent()) {
            Duration d;
            Optional<String> kafkaUpgradeEndTimestampAnnotation = managedKafka.getAnnotation(Annotations.KAFKA_UPGRADE_END_TIMESTAMP);

            // Kafka upgrade just finished
            if (kafkaUpgradeEndTimestampAnnotation.isEmpty()) {
                // job scheduled at full configured time
                d = Duration.ofMillis(checkStabilityTimeMs);
                log.debugf("[%s/%s] No %s annotation, duration is %s",
                        managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(),
                        Annotations.KAFKA_UPGRADE_END_TIMESTAMP, d.toString());

                // stamp the ManagedKafka resource with ending time of Kafka upgrade and starting stability check
                addUpgradeTimeStampAnnotation(managedKafka, Annotations.KAFKA_UPGRADE_END_TIMESTAMP);
            // Kafka upgrade already finished, but operator could have crashed not ending the stability check (and removing annotations)
            } else {
                ZonedDateTime endTimestamp = ZonedDateTime.parse(kafkaUpgradeEndTimestampAnnotation.get());
                log.debugf("[%s/%s] Found %s annotation, end timestamp is %s",
                        managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(),
                        Annotations.KAFKA_UPGRADE_END_TIMESTAMP, endTimestamp);

                // if the time window to wait is already passed (i.e. fleetshard operator was down for long time)
                if (ZonedDateTime.now(ZoneOffset.UTC).compareTo(endTimestamp.plus(checkStabilityTimeMs, ChronoUnit.MILLIS)) > 0) {
                    // job scheduled to run immediately
                    d = Duration.ZERO;
                    log.debugf("[%s/%s] Current time exceeds end timestamp, duration is %s",
                            managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), d.toString());
                } else {
                    // job scheduled for the remaining time window
                    d = Duration.between(ZonedDateTime.now(ZoneOffset.UTC), endTimestamp.plus(checkStabilityTimeMs, ChronoUnit.MILLIS));
                    log.debugf("[%s/%s] Remaining duration is %s",
                            managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), d.toString());
                }
            }
            log.infof("[%s/%s] Triggering upgrade stability check in %s",
                    managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), d.toString());

            JobDetail job = JobBuilder.newJob(CheckKafkaUpgradeIsStableJob.class)
                    .withIdentity(managedKafka.getMetadata().getName() + "-canary-job")
                    .build();
            job.getJobDataMap().put("managed-kafka", managedKafka);

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(managedKafka.getMetadata().getName() + "-canary-trigger")
                    .startAt(Date.from(ZonedDateTime.now(ZoneOffset.UTC).plus(d).toInstant()))
                    .forJob(job)
                    .build();

            try {
                quartz.scheduleJob(job, trigger);
            } catch (SchedulerException e) {
                log.errorf("[%s/%s] Error scheduling the Kafka upgrade stability check job",
                        managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), e);
            }
        }
    }

    /**
     * Scheduled job to execute the Kafka stability check
     *
     * @param managedKafka ManagedKafka instance
     */
    void doKafkaUpgradeStabilityCheck(ManagedKafka managedKafka) {
        if (!stabilityCheckEnabled) {
            log.warnf("Skipping [%s/%s] Kafka upgrade stability check", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());
            managedKafkaClient
                    .inNamespace(managedKafka.getMetadata().getNamespace())
                    .withName(managedKafka.getMetadata().getName())
                    .edit(mk -> new ManagedKafkaBuilder(mk)
                            .editMetadata()
                                .removeFromAnnotations(ManagedKafkaKeys.Annotations.KAFKA_UPGRADE_START_TIMESTAMP)
                                .removeFromAnnotations(ManagedKafkaKeys.Annotations.KAFKA_UPGRADE_END_TIMESTAMP)
                            .endMetadata()
                            .build());
            informerManager.resyncManagedKafka(managedKafka);
            return;
        }

        log.infof("[%s/%s] Kafka upgrade stability check", managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());

        CanaryService canaryService = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://" + AbstractCanary.canaryName(managedKafka) + "." + managedKafka.getMetadata().getNamespace() + ":8080"))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(CanaryService.class);

        try {
            Status status = canaryService.getStatus();
            log.infof("[%s/%s] Canary status: timeWindow %d - percentage %d",
                    managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(),
                    status.getConsuming().getTimeWindow(), status.getConsuming().getPercentage());

            if (status.getConsuming().getPercentage() > consumingPercentageThreshold) {
                log.debugf("[%s/%s] Remove Kafka upgrade start/end annotations",
                        managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());

                managedKafkaClient
                        .inNamespace(managedKafka.getMetadata().getNamespace())
                        .withName(managedKafka.getMetadata().getName())
                        .edit(mk -> new ManagedKafkaBuilder(mk)
                                .editMetadata()
                                    .removeFromAnnotations(Annotations.KAFKA_UPGRADE_START_TIMESTAMP)
                                    .removeFromAnnotations(Annotations.KAFKA_UPGRADE_END_TIMESTAMP)
                                .endMetadata()
                                .build());
            } else {
                log.warnf("[%s/%s] Reported consuming percentage %d less than %d threshold",
                        managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(),
                        status.getConsuming().getPercentage(), consumingPercentageThreshold);

                managedKafkaClient
                        .inNamespace(managedKafka.getMetadata().getNamespace())
                        .withName(managedKafka.getMetadata().getName())
                        .edit(mk -> new ManagedKafkaBuilder(mk)
                                .editMetadata()
                                    .removeFromAnnotations(Annotations.KAFKA_UPGRADE_END_TIMESTAMP)
                                .endMetadata()
                                .build());
            }
            // trigger a reconcile on the ManagedKafka instance to push checking if next step
            // Kafka IBP upgrade is needed or another stability check
            informerManager.resyncManagedKafka(managedKafka);
        } catch (Exception e) {
            log.errorf("[%s/%s] Error while checking Kafka upgrade stability",
                    managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), e);
        }
    }

    /**
     * Returns if the job about the Kafka upgrade stability check exists in the scheduler.
     * Actually if it was scheduled.
     *
     * @param managedKafka ManagedKafka instance
     * @return if the job about the Kafka stability check exists in the scheduler
     */
    private boolean isKafkaUpgradeStabilityCheckJobExists(ManagedKafka managedKafka) {
        boolean jobExists = false;
        try {
            JobKey jobKey = JobKey.jobKey(managedKafka.getMetadata().getName() + "-canary-job");
            jobExists = quartz.checkExists(jobKey);
        } catch (SchedulerException e) {
            log.errorf("[%s/%s] Error while checking if canary job exists",
                    managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), e);
        }
        return jobExists;
    }

    /**
     * Add a Kafka upgrade related timestamp (current UTC time) to the ManagedKafka instance
     *
     * @param managedKafka ManagedKafka instance
     * @param annotation annotation to add, start or end of Kafka upgrade
     */
    private void addUpgradeTimeStampAnnotation(ManagedKafka managedKafka, String annotation) {
        log.debugf("[%s/%s] Adding Kafka upgrade %s timestamp annotation",
                managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName(), annotation);
        managedKafkaClient
                .inNamespace(managedKafka.getMetadata().getNamespace())
                .withName(managedKafka.getMetadata().getName())
                .edit(mk -> new ManagedKafkaBuilder(mk)
                        .editMetadata()
                            .addToAnnotations(annotation, ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT))
                        .endMetadata()
                        .build());
    }

    public static class CheckKafkaUpgradeIsStableJob implements Job {

        @Inject
        KafkaManager kafkaManager;

        @Override
        public void execute(JobExecutionContext jobExecutionContext) {
            ManagedKafka managedKafka = (ManagedKafka) jobExecutionContext.getJobDetail().getJobDataMap().get("managed-kafka");
            kafkaManager.doKafkaUpgradeStabilityCheck(managedKafka);
        }
    }

    private Kafka cachedKafka(ManagedKafka managedKafka) {
        return informerManager.getLocalKafka(AbstractKafkaCluster.kafkaClusterNamespace(managedKafka), AbstractKafkaCluster.kafkaClusterName(managedKafka));
    }
}
