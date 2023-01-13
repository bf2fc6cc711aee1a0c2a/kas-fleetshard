package org.bf2.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.zjsonpatch.JsonDiff;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import org.bf2.common.ConditionUtils;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.common.OperandUtils;
import org.bf2.common.health.SystemTerminator;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Type;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import org.jboss.logging.NDC;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * Has the responsibility of processing the remote list of ManagedKafka from the
 * control plane.
 *
 * Actual modifications to the kube ManagedKafka are added to a simple work
 * queue, but you could envision using a more involved approach
 * https://github.com/kubernetes/client-go/tree/master/examples/workqueue -
 * where tasks could be superseded, rate limited, etc.
 */
@ApplicationScoped
public class ManagedKafkaSync {
    private static final String MANAGEDKAFKA_ID_LABEL = "bf2.org/id";
    private static final String MANAGEDKAFKA_ID_NAMESPACE_LABEL = "bf2.org/managedkafka-id";
    private static Logger log = Logger.getLogger(ManagedKafkaSync.class);

    @Inject
    ManagedKafkaResourceClient client;

    @Inject
    LocalLookup lookup;

    @Inject
    ControlPlane controlPlane;

    @Inject
    KubernetesClient kubeClient;

    @Inject
    ExecutorService executorService;

    @Inject
    protected SecretManager secretManager;

    @Inject
    SystemTerminator systemTerminator;

    /**
     * Update the local state based upon the remote ManagedKafkas
     * The strategy here is to take a pass over the list and find any deferred work
     * Then execute that deferred work using the {@link ManagedExecutor} but with
     * a refresh of the state to ensure we're still acting appropriately.
     */
    @Timed(value = "sync.poll", extraTags = {"resource", "ManagedKafka"}, description = "The time spent processing polling calls")
    @Counted(value = "sync.poll", extraTags = {"resource", "ManagedKafka"}, description = "The number of polling calls")
    public void syncKafkaClusters() {
        Map<String, ManagedKafka> remotes = new HashMap<>();

        for (ManagedKafka remoteManagedKafka : controlPlane.getKafkaClusters()) {
            // these are basically assertions - there's not expected to fail in a real environment
            Objects.requireNonNull(remoteManagedKafka.getId());
            Objects.requireNonNull(remoteManagedKafka.getMetadata().getNamespace());

            remotes.put(ControlPlane.managedKafkaKey(remoteManagedKafka), remoteManagedKafka);
            ManagedKafkaSpec remoteSpec = remoteManagedKafka.getSpec();
            Objects.requireNonNull(remoteSpec);

            String localKey = Cache.namespaceKeyFunc(remoteManagedKafka.getMetadata().getNamespace(), remoteManagedKafka.getMetadata().getName());
            ManagedKafka existing = lookup.getLocalManagedKafka(localKey);

            // take action based upon differences
            // this is really just seeing if an instance needs created and the delete flag
            // there are no other fields to reconcile - but you could envision updating
            // component versions etc. later

            if (existing == null) {
                if (!remoteSpec.isDeleted()) {
                    reconcileAsync(ControlPlane.managedKafkaKey(remoteManagedKafka), localKey);
                } else if (isConsistent(remoteManagedKafka)) {
                    log.warnf("ManagedKafka %s no longer exists, sending the control plane a dummy deleted status", Cache.metaNamespaceKeyFunc(remoteManagedKafka));
                    // we've successfully removed locally, but control plane is not aware
                    // we need to send another status update to let them know

                    ManagedKafkaStatusBuilder statusBuilder = new ManagedKafkaStatusBuilder();
                    statusBuilder.withConditions(ConditionUtils.buildCondition(Type.Ready, Status.False).reason(Reason.Deleted));
                    // fire and forget the async call - if it fails, we'll retry on the next poll
                    controlPlane.updateKafkaClusterStatus(()->{return Map.of(remoteManagedKafka.getId(), statusBuilder.build());});
                }
            } else {
                final String localNamespace = existing.getMetadata().getNamespace();
                final String managedKafkaId = existing.getMetadata().getAnnotations() == null ? null : existing.getMetadata().getAnnotations().get(MANAGEDKAFKA_ID_LABEL);
                Namespace n = kubeClient.namespaces().withName(localNamespace).get();
                if (n != null) {
                    String namespaceLabel = Optional.ofNullable(n.getMetadata().getLabels()).map(m -> m.get(MANAGEDKAFKA_ID_NAMESPACE_LABEL)).orElse("");
                    if (managedKafkaId != null && !namespaceLabel.equals(managedKafkaId)) {
                        kubeClient.namespaces().withName(localNamespace).edit(namespace -> new NamespaceBuilder(namespace)
                                .editMetadata()
                                .addToLabels(MANAGEDKAFKA_ID_NAMESPACE_LABEL, managedKafkaId)
                                .endMetadata()
                                .build());
                    }
                }

                if (changed(remoteManagedKafka, existing)) {
                    reconcileAsync(ControlPlane.managedKafkaKey(remoteManagedKafka), localKey);
                }
            }
        }

        // process final removals
        for (ManagedKafka local : lookup.getLocalManagedKafkas()) {
            if (remotes.get(ControlPlane.managedKafkaKey(local)) != null || !deleteAllowed(local)) {
                continue;
            }

            reconcileAsync(null, Cache.metaNamespaceKeyFunc(local));
        }

    }

    boolean deleteAllowed(ManagedKafka local) {
        if (local.getId() == null) {
            return false; // not a managed instance
        }
        if (!local.getSpec().isDeleted()) {
            if (local.isReserveDeployment()) {
                return true;
            }
            if (local.getStatus() != null
                    && ConditionUtils.findManagedKafkaCondition(local.getStatus().getConditions(), Type.Ready)
                            .filter(c -> Reason.Rejected.name().equals(c.getReason())).isPresent()) {
                return true;
            }
            log.warnf("Control plane wants to fully remove %s, but it's not marked as fully deleted", Cache.metaNamespaceKeyFunc(local));
            return false;
        }
        return true;
    }

    public boolean changed(ManagedKafka remote, ManagedKafka existing) {
        ManagedKafkaSpec remoteSpec = remote.getSpec();
        if (!remoteSpec.isDeleted() && existing.getSpec().isDeleted()) {
            // TODO: seems like a problem / resurrection - should not happen
            log.warnf("Ignoring ManagedKafka %s that wants to come back to life", Cache.metaNamespaceKeyFunc(existing));
            return false;
        }
        if (secretManager.isMasterSecretChanged(remote, existing)) {
            log.debugf("Remote master secret data changed");
            return true;
        }

        ManagedKafka remoteCopy = secretManager.removeSecretsFromManagedKafka(remote);

        if (!remoteCopy.getSpec().equals(existing.getSpec())) {
            logChange("Remote spec changed: %s", remoteCopy.getSpec(), existing.getSpec());
            return true;
        }

        if (!Objects.equals(existing.getMetadata().getLabels(), remoteCopy.getMetadata().getLabels())) {
            logChange("Remote labels changed: %s", remoteCopy.getMetadata().getLabels(), existing.getMetadata().getLabels());
            return true;
        }

        // will always be non-null due to the id/placement requirements
        Map<String, String> annotations = new HashMap<>(existing.getMetadata().getAnnotations());
        annotations.keySet().removeAll(ManagedKafkaKeys.Annotations.DATA_PLANE_ANNOTATIONS);

        if (!Objects.equals(annotations, remoteCopy.getMetadata().getAnnotations())) {
            logChange("Remote annotations changed: %s", remoteCopy.getMetadata().getAnnotations(), annotations);
            return true;
        }

        return false;
    }

    void logChange(String format, Object remote, Object local) {
        if (log.isDebugEnabled()) {
            ObjectMapper objectMapper = Serialization.yamlMapper();
            JsonNode localJson = objectMapper.convertValue(local, JsonNode.class);
            JsonNode remoteJson = objectMapper.convertValue(remote, JsonNode.class);
            JsonNode patch = JsonDiff.asJson(localJson, remoteJson);
            log.debugf(format, patch.toPrettyString());
        }
    }

    /**
     * @param remoteId - obtained from {@link ControlPlane#managedKafkaKey(ManagedKafka)}
     * @param localMetaNamespaceKey - obtained from {@link Cache#namespaceKeyFunc(String, String)}
     */
    void reconcileAsync(String remoteId, String localMetaNamespaceKey) {
        executorService.execute(() -> {
            reconcile(remoteId, localMetaNamespaceKey);
        });
    }

    /**
     * Final sync processing of the remote vs. local state
     */
    void reconcile(String remoteId, String localMetaNamespaceKey) {
        ManagedKafka local = null;
        if (localMetaNamespaceKey != null) {
            //refresh the local
            local = lookup.getLocalManagedKafka(localMetaNamespaceKey);
        }

        ManagedKafka remote = null;
        if (remoteId != null) {
            //refresh the remote
            remote = controlPlane.getDesiredState(remoteId);
        }

        if (local == null && remote == null) {
            return; //nothing to do
        }

        String id = null;
        if (local != null) {
            id = local.getId();
        } else {
            id = remote.getId();
        }
        if (id != null) {
            NDC.push(ManagedKafkaResourceClient.ID_LOG_KEY + "=" + id);
        }
        try {
            if (local == null) {
                if (!remote.getSpec().isDeleted()) {
                    create(remote);
                }
            } else if (remote == null) {
                if (deleteAllowed(local)) {
                    delete(local);
                }
            } else {
                if (!Objects.equals(local.getPlacementId(), remote.getPlacementId())) {
                    log.debugf("Waiting for existing ManagedKafka %s to disappear before attempting next placement", local.getPlacementId());
                    return;
                }
                if (changed(remote, local)) {
                    log.debugf("Updating ManagedKafka %s", Cache.metaNamespaceKeyFunc(local));
                    Secret masterSecret = secretManager.buildSecret(remote);
                    ManagedKafka remoteCopy = secretManager.removeSecretsFromManagedKafka(remote);
                    ManagedKafkaSpec spec = remoteCopy.getSpec();
                    ObjectMeta meta = remoteCopy.getMetadata();
                    client.edit(local.getMetadata().getNamespace(), local.getMetadata().getName(), mk -> {
                            mk.getMetadata().setLabels(meta.getLabels());
                            Map<String, String> existingAnnotations = mk.getMetadata().getAnnotations();
                            mk.getMetadata().setAnnotations(meta.getAnnotations());
                            if (existingAnnotations != null) {
                                existingAnnotations.keySet().retainAll(ManagedKafkaKeys.Annotations.DATA_PLANE_ANNOTATIONS);
                                mk.getMetadata().getAnnotations().putAll(existingAnnotations);
                            }
                            secretManager.calculateMasterSecretDigest(mk, masterSecret);
                            mk.setSpec(spec);
                            return mk;
                        });
                    secretManager.createOrUpdateSecret(local, masterSecret);
                    // the operator will handle it from here
                }
            }
        } finally {
            if (id != null) {
                NDC.pop();
            }
        }
    }

    void delete(ManagedKafka local) {
        log.debugf("Deleting ManagedKafka %s", Cache.metaNamespaceKeyFunc(local));

        client.delete(local.getMetadata().getNamespace(), local.getMetadata().getName());

        kubeClient.namespaces().withName(local.getMetadata().getNamespace()).delete();

        // only remove the local after we're fully cleaned up, so that
        // we'll keep retrying if there is a failure
        controlPlane.removeDesiredState(local);
    }

    void create(ManagedKafka remote) {
        // log after the namespace is set
        log.debugf("Creating ManagedKafka %s", Cache.metaNamespaceKeyFunc(remote));
        final String remoteNamespace = remote.getMetadata().getNamespace();
        final String remoteManagedKafkaId = remote.getMetadata().getAnnotations() == null ? null : remote.getMetadata().getAnnotations().get(MANAGEDKAFKA_ID_LABEL);

        kubeClient.namespaces().createOrReplace(
                new NamespaceBuilder()
                        .withNewMetadata()
                            .withName(remoteNamespace)
                            .withLabels(OperandUtils.getDefaultLabels())
                            .addToLabels("observability-operator/scrape-logging", "true")
                            .addToLabels(MANAGEDKAFKA_ID_NAMESPACE_LABEL, remoteManagedKafkaId)
                        .endMetadata()
                        .build());

        //Creating the master Secrets
       Secret secret = secretManager.buildSecret(remote);
       remote = secretManager.removeSecretsFromManagedKafka(remote);
       secretManager.calculateMasterSecretDigest(remote, secret);

       try {
           remote = client.create(remote);
           secretManager.createOrUpdateSecret(remote, secret);
       } catch (KubernetesClientException e) {
           if (e.getStatus().getCode() != HttpURLConnection.HTTP_CONFLICT) {
               throw e;
           }
           // it's possible, but unlikely, that we'll see two reconciliations close enough together
           // such that we'll naturally see a conflict - that is the second completes before the cache
           // is updated.  It's more likely that the cache state is not up-to-date, so we'll just
           // let an exception happen in that case
           isConsistent(remote);
       }
    }

    /**
     * Check if the cache state is consistent with the expectation that the entry does not exist in cache
     * @return true if consistent
     */
    private boolean isConsistent(ManagedKafka remote) {
        ManagedKafka existing = kubeClient.resources(ManagedKafka.class)
                .inNamespace(remote.getMetadata().getNamespace())
                .withName(remote.getMetadata().getName()).get();
        if (existing != null) {
            checkCreationTimestamp(existing, Duration.ofMinutes(1));
            throw new RuntimeException(String.format("ManagedKafka %s exists, but not in the informer cache.  The sync may not be in a healthy state", Cache.metaNamespaceKeyFunc(remote)));
        }
        return true;
    }

    void checkCreationTimestamp(ManagedKafka existing, Duration duration) {
        if (KubernetesResourceUtil.getAge(existing).compareTo(duration) > 0) {
            systemTerminator.notifyUnhealthy();
        }
    }

    @Scheduled(every = "{poll.interval}", delayed = "{poll.delay}", concurrentExecution = ConcurrentExecution.SKIP)
    void pollKafkaClusters() {
        log.debug("Polling for control plane managed kafkas");
        // TODO: this is based upon a full poll - eventually this could be
        // based upon a delta revision / timestmap to get a smaller list
        syncKafkaClusters();
    }
}
