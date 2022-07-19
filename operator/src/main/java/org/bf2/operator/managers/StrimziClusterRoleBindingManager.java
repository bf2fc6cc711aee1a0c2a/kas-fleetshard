package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.quarkus.scheduler.ScheduledExecution;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class StrimziClusterRoleBindingManager implements Scheduled.SkipPredicate {

    static final Map<String, String> STRIMZI_CRB_LABELS = Map.of(
            "app.kubernetes.io/managed-by", "strimzi-cluster-operator",
            "strimzi.io/kind", "Kafka");
    static final String STRIMZI_KAFKA_ROLEREF = "strimzi-kafka-broker";

    @Inject
    Logger log;

    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "strimzi.clusterrolebinding-scan.enabled", defaultValue = "true")
    boolean scanEnabled;

    @Scheduled(
            every = "{strimzi.clusterrolebinding-scan.interval}",
            delay = 1,
            concurrentExecution = ConcurrentExecution.SKIP,
            skipExecutionIf = StrimziClusterRoleBindingManager.class)
    void removeAbandonedClusterRoleBindings() {
        Set<String> namespaces = client.namespaces()
                .list()
                .getItems()
                .stream()
                .map(Namespace::getMetadata)
                .map(ObjectMeta::getName)
                .collect(Collectors.toSet());

        List<ClusterRoleBinding> abandonedBindings = client.rbac()
                .clusterRoleBindings()
                .withLabels(STRIMZI_CRB_LABELS)
                .list()
                .getItems()
                .stream()
                .filter(crb -> STRIMZI_KAFKA_ROLEREF.equals(crb.getRoleRef().getName()))
                .filter(crb -> crb.getSubjects().stream().map(Subject::getNamespace).noneMatch(namespaces::contains))
                .collect(Collectors.toList());

        if (abandonedBindings.isEmpty()) {
            log.infof("No abandoned '%s' ClusterRoleBindings found", STRIMZI_KAFKA_ROLEREF);
        } else {
            log.infof("Found %d '%s' ClusterRoleBindings referencing a non-existent namespace", abandonedBindings.size(), STRIMZI_KAFKA_ROLEREF);
            abandonedBindings.forEach(crb -> {
                try {
                    log.infof("ClusterRoleBinding %s will be deleted", crb.getMetadata().getName());
                    client.rbac().clusterRoleBindings().delete(crb);
                } catch (Exception e) {
                    log.warnf(e, "Unexpected exception deleting ClusterRoleBinding %s", crb.getMetadata().getName());
                }
            });
            log.infof("Removal of abandoned '%s' ClusterRoleBindings complete", STRIMZI_KAFKA_ROLEREF);
        }
    }

    @Override
    public boolean test(ScheduledExecution execution) {
        return !scanEnabled;
    }
}
