package org.bf2.common;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodAffinityBuilder;
import io.fabric8.kubernetes.api.model.PodAffinityTermBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class OperandUtils {

    public static final String FLEETSHARD_OPERATOR_NAME = "kas-fleetshard-operator";

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
        result.put("app.kubernetes.io/managed-by", FLEETSHARD_OPERATOR_NAME);
        return result;
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

    public static Affinity kafkaPodAffinity(ManagedKafka managedKafka) {
        // place where kafka is placed
        LinkedHashMap<String, String> kafkaPodSelectorLabels = new LinkedHashMap<>(1);
        kafkaPodSelectorLabels.put("strimzi.io/name",  managedKafka.getMetadata().getName()+"-kafka");

        return new AffinityBuilder().withPodAffinity(new PodAffinityBuilder()
            .withRequiredDuringSchedulingIgnoredDuringExecution(new PodAffinityTermBuilder()
                    .withTopologyKey("kubernetes.io/hostname")
                    .withNewLabelSelector()
                        .withMatchLabels(kafkaPodSelectorLabels)
                    .endLabelSelector()
                    .build())
            .build())
        .build();
    }
}
