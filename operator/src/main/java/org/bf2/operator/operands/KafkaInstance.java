package org.bf2.operator.operands;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.managers.MetricsManager;
import org.bf2.operator.managers.SecuritySecretManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents an overall Kafka instance made by Kafka, Canary and AdminServer resources
 */
@ApplicationScoped
public class KafkaInstance implements Operand<ManagedKafka> {

    @Inject
    Logger log;
    @Inject
    AbstractKafkaCluster kafkaCluster;
    @Inject
    Canary canary;
    @Inject
    AdminServer adminServer;
    @Inject
    ImagePullSecretManager imagePullSecretManager;
    @Inject
    SecuritySecretManager securitySecretManager;
    @Inject
    MetricsManager metricsManager;

    private final Deque<Operand<ManagedKafka>> operands = new ArrayDeque<>();

    @PostConstruct
    void init() {
        operands.addAll(Arrays.asList(kafkaCluster, canary, adminServer));
    }

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        metricsManager.createOrUpdateMetrics(managedKafka);

        if (managedKafka.getAnnotation(ManagedKafkaKeys.Annotations.PAUSE_RECONCILIATION).map(Boolean::valueOf).orElse(false)) {
            return;
        }

        imagePullSecretManager.propagateSecrets(managedKafka);

        if (securitySecretManager.masterSecretExists(managedKafka)) {
            operands.forEach(o -> o.createOrUpdate(managedKafka));
        } else {
            log.infof("Master secret not yet created, skipping create/update processing");
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context context) {
        imagePullSecretManager.deleteSecrets(managedKafka);

        // The deletion order is significant. The canary is deleted before the cluster so that the
        // collection of metrics from a de-provision cluster is avoided.
        operands.descendingIterator().forEachRemaining(o -> o.delete(managedKafka, context));
        metricsManager.deleteMetrics(managedKafka);
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        return operands.stream().allMatch(o -> o.isDeleted(managedKafka));
    }

    public AbstractKafkaCluster getKafkaCluster() {
        return kafkaCluster;
    }

    public Canary getCanary() {
        return canary;
    }

    public AdminServer getAdminServer() {
        return adminServer;
    }

    @Override
    public OperandReadiness getReadiness(ManagedKafka managedKafka) {
        if (managedKafka.getSpec().isDeleted()) {
            // TODO: it may be a good idea to offer a message here as well
            return new OperandReadiness(isDeleted(managedKafka) ? Status.False : Status.Unknown, Reason.Deleted, null);
        }
        if (managedKafka.isSuspended()) {
            OperandReadiness kafkaReadiness = kafkaCluster.getReadiness(managedKafka);
            return new OperandReadiness(Status.False, Reason.Suspended, kafkaReadiness.getMessage());
        }
        if (managedKafka.getAnnotation(ManagedKafkaKeys.Annotations.PAUSE_RECONCILIATION).map(Boolean::valueOf).orElse(false)) {
            return new OperandReadiness(Status.Unknown, Reason.Paused, "Reconciliation paused via annotation");
        }
        List<OperandReadiness> readiness = operands.stream().map(o -> o.getReadiness(managedKafka)).filter(Objects::nonNull).collect(Collectors.toList());

        return combineReadiness(readiness);
    }

    public static OperandReadiness combineReadiness(List<OperandReadiness> readiness) {
        // default to the first reason, with can come from the kafka by the order of the operands
        Reason reason = readiness.stream().map(OperandReadiness::getReason).filter(Objects::nonNull).findFirst().orElse(null);
        // default the status to false or unknown if any are unknown
        Status status = readiness.stream().anyMatch(r -> Status.Unknown.equals(r.getStatus())) ? Status.Unknown : Status.False;
        // combine all the messages
        String message = readiness.stream().map(OperandReadiness::getMessage).filter(Objects::nonNull).collect(Collectors.joining("; "));

        // override in particular scenarios
        if (readiness.stream().allMatch(r -> Status.True.equals(r.getStatus()))) {
            status = Status.True;
        } else if (readiness.stream().anyMatch(r -> Reason.Installing.equals(r.getReason()))) {
            reason = Reason.Installing; // may mask other error states
        } else if (readiness.stream().anyMatch(r -> Reason.Error.equals(r.getReason()))) {
            reason = Reason.Error;
        }
        return new OperandReadiness(status, reason, message);
    }
}
