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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OperandUtils {

    public static final String K8S_NAME_LABEL = "app.kubernetes.io/name";
    public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    public static final String COMPONENT_LABEL = "app.kubernetes.io/component";

    public static final String OPENSHIFT_RATE_LIMIT_ANNOTATION = "haproxy.router.openshift.io/rate-limit-connections";
    public static final String OPENSHIFT_RATE_LIMIT_ANNOTATION_CONCURRENT_TCP = OPENSHIFT_RATE_LIMIT_ANNOTATION + ".concurrent-tcp";
    public static final String OPENSHIFT_RATE_LIMIT_ANNOTATION_TCP_RATE = OPENSHIFT_RATE_LIMIT_ANNOTATION + ".rate-tcp";
    public static final String OPENSHIFT_INGRESS_BALANCE = "haproxy.router.openshift.io/balance";
    public static final String OPENSHIFT_INGRESS_BALANCE_LEASTCONN = "leastconn";

    public static final String STRIMZI_OPERATOR_NAME = "strimzi-cluster-operator";
    public static final String FLEETSHARD_OPERATOR_NAME = "kas-fleetshard-operator";
    public static final String MASTER_SECRET_NAME = "master-secret";
    public static final String INGRESS_TYPE = "ingressType";
    public static final String SHARDED = "sharded";

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

    public static List<Toleration> profileTolerations(ManagedKafka managedKafka, ManagedKafkaAgent agent, boolean dynamicScalingScheduling) {
        String type =
                OperandUtils.getOrDefault(managedKafka.getMetadata().getLabels(), ManagedKafka.PROFILE_TYPE, null);

        if (type == null) {
            return Collections.emptyList();
        }

        ArrayList<Toleration> result = new ArrayList<>();
        result.add(new TolerationBuilder()
                .withKey(ManagedKafka.PROFILE_TYPE)
                .withValue(type)
                .withEffect("NoExecute")
                .build());

        // add only with a version that supports dynamicScalingScheduling to control the blast radius
        if (dynamicScalingScheduling && shouldProfileLabelsExist(agent)) {
            result.add(new TolerationBuilder()
                    .withKey(ManagedKafka.PROFILE_TYPE)
                    .withValue(type)
                    .withEffect("NoSchedule")
                    .build());
        }

        return result;
    }

    public static NodeAffinity nodeAffinity(ManagedKafkaAgent agent, ManagedKafka managedKafka) {
        if (!shouldProfileLabelsExist(agent)) {
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

    public static boolean shouldProfileLabelsExist(ManagedKafkaAgent agent) {
        return agent != null && !agent.getSpec().getCapacity().isEmpty();
    }

    /**
     * Create the Affinity based upon the Agent/profile and if it should be collocated with the zookeeper
     */
    public static Affinity buildAffinity(ManagedKafkaAgent agent, ManagedKafka managedKafka, boolean collocateWithZookeeper, boolean useNodeAffinity) {
        AffinityBuilder affinityBuilder = new AffinityBuilder();
        boolean useAffinity = false;
        if (useNodeAffinity) {
            NodeAffinity nodeAffinity = nodeAffinity(agent, managedKafka);
            if (nodeAffinity != null) {
                affinityBuilder.withNodeAffinity(nodeAffinity);
                useAffinity = true;
            }
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

    /**
     * Build a map of OpenShift Route annotations with the provided concurrent TCP connection
     * and TCP connection rate limits.
     *
     * @param concurrentTcp number of concurrent TCP connections allowed per IP
     * @param rateTcp TCP connection creation rate per second allowed per IP
     * @return map of OpenShift Route rate limit annotations
     */
    public static Map<String, String> buildRateLimitAnnotations(int concurrentTcp, int rateTcp) {
        return Map.ofEntries(
                Map.entry(OPENSHIFT_RATE_LIMIT_ANNOTATION, "true"),
                Map.entry(OPENSHIFT_RATE_LIMIT_ANNOTATION_CONCURRENT_TCP, String.valueOf(concurrentTcp)),
                // TCP limit expressed in terms of a 3s window
                Map.entry(OPENSHIFT_RATE_LIMIT_ANNOTATION_TCP_RATE, String.valueOf(3 * rateTcp)));
    }

    public static <T> T getOrDefault(Map<String, T> map, String key, T defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        return map.getOrDefault(key, defaultValue);
    }
}
