package org.bf2.common;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelectorRequirementBuilder;
import io.fabric8.kubernetes.api.model.NodeAffinity;
import io.fabric8.kubernetes.api.model.NodeAffinityBuilder;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirementBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodAffinity;
import io.fabric8.kubernetes.api.model.PodAffinityBuilder;
import io.fabric8.kubernetes.api.model.PodAffinityTerm;
import io.fabric8.kubernetes.api.model.PodAffinityTermBuilder;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OperandUtils {

    public static final String K8S_NAME_LABEL = "app.kubernetes.io/name";
    public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    public static final String COMPONENT_LABEL = "app.kubernetes.io/component";
    public static final String STRIMZI_OPERATOR_NAME = "strimzi-cluster-operator";
    public static final String FLEETSHARD_OPERATOR_NAME = "kas-fleetshard-operator";
    public static final String MASTER_SECRET_NAME = "master-secret";

    /**
     * Set the provided resource as owner of the resource
     *
     * @param owner instance as owner
     * @param resource resource to set the owner
     */
    public static void setAsOwner(HasMetadata owner, HasMetadata resource) {
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(owner.getApiVersion())
                .withKind(owner.getKind())
                .withName(owner.getMetadata().getName())
                .withUid(owner.getMetadata().getUid())
                .build();
        resource.getMetadata().setOwnerReferences(Collections.singletonList(ownerReference));
    }

    public static Map<String, String> getDefaultLabels() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(1);
        result.put(MANAGED_BY_LABEL, FLEETSHARD_OPERATOR_NAME);
        return result;
    }

    public static Map<String, String> getMasterSecretLabel() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(1);
        result.put(COMPONENT_LABEL, MASTER_SECRET_NAME);
        return result;
    }

    public static String masterSecretName(ManagedKafka managedKafka) {
        return managedKafka.getMetadata().getName() + "-" + MASTER_SECRET_NAME;
    }

    /**
     * Similar to the fabric8 createOrReplace operation, but assumes replacement is the dominant operation
     *
     * <br>WARNING: should not be called on resources that have metadata changes that
     * need preserved.  An edit should be used instead.
     */
    public static <T extends HasMetadata> T createOrUpdate(MixedOperation<T, ?, ?> resources, T resource) {
        Resource<T> withName = resources
                .inNamespace(resource.getMetadata().getNamespace())
                .withName(resource.getMetadata().getName());
        T result = null;
        try {
            // this could be a patch(item) or replace(item) - they do similar things
            // do the replace first that's 1 call when locked
            result = withName.lockResourceVersion(resource.getMetadata().getResourceVersion()).replace(resource);
        } catch (NullPointerException | KubernetesClientException e) {
            // see https://github.com/fabric8io/kubernetes-client/issues/3121
            // see https://github.com/fabric8io/kubernetes-client/issues/3122
        }
        if (result != null) {
            return result;
        }
        return withName.createOrReplace(resource);
    }

    /**
     * Will likely always be a single toleration, but returns a list to gracefully handle null as empty
     */
    public static List<Toleration> profileTolerations(ManagedKafka managedKafka) {
        String type =
                OperandUtils.getOrDefault(managedKafka.getMetadata().getLabels(), ManagedKafka.PROFILE_TYPE, null);

        if (type == null) {
            return Collections.emptyList();
        }

        return Collections.singletonList(new TolerationBuilder()
                .withKey(ManagedKafka.PROFILE_TYPE)
                .withValue(type)
                .withEffect("NoExecute")
                .build());
    }

    public static NodeAffinity nodeAffinity(ManagedKafkaAgent agent, ManagedKafka managedKafka) {
        if (agent == null || agent.getSpec().getCapacity().size() <= 1) {
            return null; // not expected to use a node label
        }
        String type =
                OperandUtils.getOrDefault(managedKafka.getMetadata().getLabels(), ManagedKafka.PROFILE_TYPE, null);
        if (type == null) {
            return null;
        }
        return new NodeAffinityBuilder().withNewRequiredDuringSchedulingIgnoredDuringExecution()
                .addNewNodeSelectorTerm()
                .addToMatchExpressions(new NodeSelectorRequirementBuilder()
                        .withKey(ManagedKafka.PROFILE_TYPE)
                        .withOperator("In")
                        .withValues(type)
                        .build())
                .endNodeSelectorTerm()
                .endRequiredDuringSchedulingIgnoredDuringExecution()
                .build();
    }

    /**
     * Create the Affinity based upon the Agent/profile and if it should be collocated with the zookeeper
     */
    public static Affinity buildAffinity(ManagedKafkaAgent agent, ManagedKafka managedKafka, boolean collocateWithZookeeper) {
        AffinityBuilder affinityBuilder = new AffinityBuilder();
        boolean useAffinity = false;
        NodeAffinity nodeAffinity = nodeAffinity(agent, managedKafka);
        if (nodeAffinity != null) {
            affinityBuilder.withNodeAffinity(nodeAffinity);
            useAffinity = true;
        }
        if(collocateWithZookeeper) {
            affinityBuilder.withPodAffinity(buildZookeeperPodAffinity(managedKafka));
            useAffinity = true;
        }
        if (useAffinity) {
            return affinityBuilder.build();
        }
        return null;
    }

    public static PodAffinity buildZookeeperPodAffinity(ManagedKafka managedKafka) {
        // place where zookeeper is placed
        return new PodAffinityBuilder().addNewPreferredDuringSchedulingIgnoredDuringExecution()
                .withWeight(100)
                .withPodAffinityTerm(
                        affinityTerm("strimzi.io/name", managedKafka.getMetadata().getName() + "-zookeeper"))
                .endPreferredDuringSchedulingIgnoredDuringExecution()
                .build();
    }

    public static PodAffinityTerm affinityTerm(String key, String value) {
        return new PodAffinityTermBuilder()
                .withTopologyKey("kubernetes.io/hostname")
                .withNewLabelSelector()
                .addToMatchExpressions(new LabelSelectorRequirementBuilder()
                    .withKey(key)
                    .withOperator("In")
                    .withValues(value).build())
                .endLabelSelector().build();
    }

    public static <T> T getOrDefault(Map<String, T> map, String key, T defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        return map.getOrDefault(key, defaultValue);
    }
}
