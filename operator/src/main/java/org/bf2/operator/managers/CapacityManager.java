package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.Getter;
import lombok.Setter;
import org.bf2.common.ConditionUtils;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.common.OperandUtils;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.operands.KafkaInstanceConfigurations;
import org.bf2.operator.operands.KafkaInstanceConfigurations.InstanceType;
import org.bf2.operator.operands.OperandReadiness;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;
import org.bf2.operator.resources.v1alpha1.Profile;
import org.bf2.operator.resources.v1alpha1.ProfileCapacity;
import org.bf2.operator.resources.v1alpha1.ProfileCapacityBuilder;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Responsible for ensuring the resources for a given ManagedKafka are available
 * prior to allowing the deployment to proceed.
 */
@ApplicationScoped
public class CapacityManager {

    public static final String FLEETSHARD_RESOURCES = "kas-fleetshard-resources";
    private static final String MANAGED_KAFKA_PREFIX = "mk-"; // should not conflict with the profile names

    @Getter
    @Setter
    private static class Resources {
        private String profile;
        private int units;
    }

    @Inject
    Logger log;

    @Inject
    KubernetesClient client;

    @Inject
    InformerManager informerManager;

    @Inject
    ManagedKafkaResourceClient managedKafkaClient;

    private boolean checkedForOrphans = false;

    /**
     * Will nominally attempt to create the configmap, if it already exists the existing one will be returned
     *
     * the map is not reconciled periodically.  rather it's assumed that all controller actions are gated
     * by the capacitymanager
     */
    public synchronized ConfigMap getOrCreateResourceConfigMap(ManagedKafkaAgent agent) {
        ConfigMap configMap = getCachedResourceConfigMap();
        if (configMap != null) {
            if (!checkedForOrphans) {
                configMap = checkForOrphans(configMap);
                checkedForOrphans = true;
            }
            return configMap;
        }
        checkedForOrphans = true;

        try {
            informerManager.createKafkaInformer();
        } catch (KubernetesClientException e) {
            // no bundle is installed, lookups for kafkas will return null
        }

        Map<String, String> data = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();

        // could use the controller cache instead - but there's a possibility it may not be the complete state
        // since this is only at start up we'll just use a list
        managedKafkaClient.list()
                .stream()
                // if deleted we'll proactively assume that cleanup will work
                .filter(mk -> !mk.getSpec().isDeleted())
                // check if it has a kafka - if not we won't consider this as taking up resources yet.
                // it will need to be reconciled before it has resources
                .filter(mk -> informerManager.getLocalKafka(AbstractKafkaCluster.kafkaClusterNamespace(mk),
                        AbstractKafkaCluster.kafkaClusterName(mk)) != null)
                .forEach(mk -> {
            String currentProfile = KafkaInstanceConfigurations.getInstanceType(mk);
            Resources resources = createResources(mk, currentProfile);
            totals.compute(currentProfile, (k, v) -> (v == null ? 0 : v) + resources.units);
            data.put(getManagedKafkaKey(mk), Serialization.asJson(resources));
        });

        // update with the totals as well
        totals.forEach((k, v) -> data.put(k, String.valueOf(v)));

        return createResouceConfigMap(agent, data);
    }

    private ConfigMap createResouceConfigMap(ManagedKafkaAgent agent, Map<String, String> data) {
        ConfigMap configMap;
        if (data.isEmpty()) {
            data.put(InstanceType.STANDARD.getLowerName(), "0"); // the map logic is easier if it's non-empty, until fabric8 6 we'll get back a null data
        }

        configMap = new ConfigMapBuilder().withNewMetadata()
                .withLabels(OperandUtils.getDefaultLabels())
                .withName(FLEETSHARD_RESOURCES)
                .endMetadata()
                .withData(data)
                .build();

        // modifications to this configmap will cause another reconciliation of the managedkafkaagent status
        OperandUtils.setAsOwner(agent, configMap);

        // if this fails the caller will need to retry
        try {
            return client.configMaps()
                    .create(configMap);
        } catch (KubernetesClientException e) {
            if (e.getCode() != HttpURLConnection.HTTP_CONFLICT) {
                throw e;
            }
            return client.configMaps().withName(FLEETSHARD_RESOURCES).require();
        }
    }

    ConfigMap checkForOrphans(ConfigMap configMap) {
        boolean update = false;
        Map<String, String> newData = new LinkedHashMap<>(configMap.getData());
        for (Map.Entry<String, String> entry : configMap.getData().entrySet()) {
            if (!entry.getKey().startsWith(MANAGED_KAFKA_PREFIX)) {
                continue;
            }
            if (client.resources(ManagedKafka.class)
                    .withLabel(ManagedKafka.ID, entry.getKey().substring(MANAGED_KAFKA_PREFIX.length()))
                    .list()
                    .getItems()
                    .isEmpty()) {
                update = true;
                releaseResources(newData, entry.getKey());
            }
        }
        if (update) {
            configMap = new ConfigMapBuilder(configMap).withData(newData).build();
            client.configMaps()
                    .withName(FLEETSHARD_RESOURCES)
                    .lockResourceVersion(configMap.getMetadata().getResourceVersion())
                    .replace(configMap);
        }
        return configMap;
    }

    static Resources createResources(ManagedKafka mk, String currentProfile) {
        Resources resources = new Resources();
        resources.profile = currentProfile;
        resources.units = Integer.parseInt(
                OperandUtils.getOrDefault(mk.getMetadata().getLabels(), ManagedKafka.PROFILE_QUOTA_CONSUMED, "1"));
        return resources;
    }

    public synchronized void releaseResources(ManagedKafka managedKafka) {
        // use the latest
        ConfigMap resourceConfigMap = client.configMaps().withName(FLEETSHARD_RESOURCES).get();
        if (resourceConfigMap != null) {
            String entryKey = getManagedKafkaKey(managedKafka);
            if (releaseResources(resourceConfigMap.getData(), entryKey)) {
                client.configMaps()
                        .withName(FLEETSHARD_RESOURCES)
                        .lockResourceVersion(resourceConfigMap.getMetadata().getResourceVersion())
                        .replace(resourceConfigMap);
            }
        }
    }

    static String getManagedKafkaKey(ManagedKafka managedKafka) {
        return MANAGED_KAFKA_PREFIX + managedKafka.getId();
    }

    private boolean releaseResources(Map<String, String> resourceData, String entryKey) {
        String previous = resourceData.remove(entryKey);
        if (previous != null) {
            Resources resources = Serialization.unmarshal(previous, Resources.class);
            resourceData.computeIfPresent(resources.profile,
                    (k, v) -> String.valueOf(Integer.parseInt(v) - resources.units));
            return true;
        }
        return false;
    }

    synchronized Optional<OperandReadiness> claimResources(ManagedKafka managedKafka, String profile,
            ManagedKafkaAgent agent) {
        if (managedKafka.isReserveDeployment()) {
            return Optional.empty();
        }
        Integer max = getProfileMaxUnits(agent.getSpec().getCapacity(), profile).orElse(null);
        if (max == null) {
            return Optional.empty();
        }

        // ensure we have the latest
        ConfigMap resourceConfigMap = client.configMaps().withName(FLEETSHARD_RESOURCES).get();

        Resources resources = createResources(managedKafka, profile);

        int total = Integer.parseInt(resourceConfigMap.getData().getOrDefault(profile, "0"));

        int newTotal = resources.units + total;
        if (newTotal > max) {
            log.warnf("Rejecting the deployment of %s/%s as the cluster is full",
                    managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());
            return Optional
                    .of(new OperandReadiness(Status.False, Reason.Rejected, "Cluster has insufficient resources"));
        }

        // update the configmap in a locked manner - this may fail, but we'll retry
        resourceConfigMap.getData().put(profile, String.valueOf(newTotal));
        resourceConfigMap.getData().put(getManagedKafkaKey(managedKafka), Serialization.asJson(resources));
        client.configMaps()
                .withName(FLEETSHARD_RESOURCES)
                .lockResourceVersion(resourceConfigMap.getMetadata().getResourceVersion())
                .replace(resourceConfigMap);
        return Optional.empty();
    }

    /**
     * For now we are operating under the assumption that nothing can change about the resources used by a managed kafka
     *
     * In the future, this will need to be relaxed
     */
    Optional<OperandReadiness> validateResources(ManagedKafka managedKafka, String profile, String claimedResources) {
        Resources resources = Serialization.unmarshal(claimedResources, Resources.class);

        String message = null;

        if (managedKafka.isReserveDeployment()) {
            message = String.format("The deployment type for %s/%s was flipped to reserved",
                    managedKafka.getMetadata().getNamespace(), managedKafka.getMetadata().getName());
        }

        if (!resources.profile.equals(profile)) {
            message = String.format("The profile type for %s/%s was changed", managedKafka.getMetadata().getNamespace(),
                    managedKafka.getMetadata().getName());
        }

        if (!String.valueOf(resources.units)
                .equals(OperandUtils.getOrDefault(managedKafka.getMetadata().getLabels(),
                        ManagedKafka.PROFILE_QUOTA_CONSUMED, "1"))) {
            message = String.format("The units for %s/%s was changed", managedKafka.getMetadata().getNamespace(),
                    managedKafka.getMetadata().getName());
        }

        if (message != null) {
            log.error(message);
            return Optional.of(new OperandReadiness(Status.False, Reason.Error, message));
        }

        return Optional.empty();
    }

    public Optional<OperandReadiness> validateResources(ManagedKafka managedKafka) {
        Optional<ManagedKafkaCondition> optReady = Optional.ofNullable(managedKafka.getStatus())
                .flatMap(s -> ConditionUtils.findManagedKafkaCondition(s.getConditions(),
                        ManagedKafkaCondition.Type.Ready));
        if (optReady.filter(c -> ManagedKafkaCondition.Reason.Rejected.name().equals(c.getReason())).isPresent()) {
            // rejected for a normal deployment is terminal
            return Optional.of(new OperandReadiness(Status.False, Reason.Rejected, optReady.get().getMessage()));
        }
        ManagedKafkaAgent localAgent = informerManager.getLocalAgent();
        // eventually we may want to require the local agent exists and is configured for max nodes
        // but for now we're lax
        if (localAgent == null) {
            return Optional.empty();
        }
        String profile = KafkaInstanceConfigurations.getInstanceType(managedKafka);
        ConfigMap resourceConfigMap = getOrCreateResourceConfigMap(localAgent);
        String claimedResources = resourceConfigMap.getData().get(getManagedKafkaKey(managedKafka));
        if (claimedResources == null) {
            return claimResources(managedKafka, profile, localAgent);
        }
        return validateResources(managedKafka, profile, claimedResources);
    }

    private ConfigMap getCachedResourceConfigMap() {
        return informerManager.getLocalConfigMap(client.getNamespace(), CapacityManager.FLEETSHARD_RESOURCES);
    }

    public Map<String, ProfileCapacity> buildCapacity(ManagedKafkaAgent resource) {
        Map<String, ProfileCapacity> capacity = new LinkedHashMap<>();

        Map<String, Profile> profiles = resource.getSpec().getCapacity();

        ConfigMap resourceConfigMap = getOrCreateResourceConfigMap(resource);

        for (String key : profiles.keySet()) {
            getProfileMaxUnits(profiles, key).ifPresent(max ->
                capacity.put(key,
                        new ProfileCapacityBuilder().withMaxUnits(max)
                                .withRemainingUnits(Optional.ofNullable(resourceConfigMap.getData().get(key))
                                        .map(Integer::valueOf)
                                        .map(i -> max - i)
                                        .orElse(max))
                                .build())
            );
        }
        return capacity;
    }

    public static Optional<Integer> getProfileMaxUnits(Map<String, Profile> profiles, String key) {
        Profile value = profiles.get(key);
        if (value != null && value.getMaxNodes() != null) {
            double nodesPerUnit = InstanceType.fromLowerName(key).getNodesPerUnit();
            return Optional.of((int) Math.floor(value.getMaxNodes() / nodesPerUnit));
        }
        return Optional.empty();
    }

}
