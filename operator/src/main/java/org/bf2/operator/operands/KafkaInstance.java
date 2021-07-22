package org.bf2.operator.operands;

import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.secrets.ImagePullSecretManager;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents an overall Kafka instance made by Kafka, Canary and AdminServer resources
 */
@ApplicationScoped
public class KafkaInstance implements Operand<ManagedKafka> {

    @Inject
    AbstractKafkaCluster kafkaCluster;
    @Inject
    Canary canary;
    @Inject
    AdminServer adminServer;
    @Inject
    ImagePullSecretManager imagePullSecretManager;

    final List<Operand<ManagedKafka>> operands = new ArrayList<>();

    @PostConstruct
    void init() {
        operands.addAll(Arrays.asList(kafkaCluster, canary, adminServer));
    }

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        imagePullSecretManager.propagateSecrets(managedKafka);

        operands.forEach(o -> o.createOrUpdate(managedKafka));
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        imagePullSecretManager.deleteSecrets(managedKafka);

        operands.forEach(o -> o.delete(managedKafka, context));
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
        List<OperandReadiness> readiness = operands.stream().map(o -> o.getReadiness(managedKafka)).collect(Collectors.toList());

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
