package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.PodAffinityBuilder;
import io.fabric8.kubernetes.api.model.PodAffinityTermBuilder;
import io.fabric8.kubernetes.client.CustomResource;
import io.javaoperatorsdk.operator.api.Context;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import java.util.LinkedHashMap;

/**
 * Define common behaviour across operands related to a controller handling a specific custom resource
 * @param <T> custom resource type handled by corresponding controller
 */
public interface Operand<T extends CustomResource<?, ?>> {

    /**
     * Create or update the operand based on the provided custom resource
     *
     * @param customResource custom resource
     */
    void createOrUpdate(T customResource);

    /**
     * Delete the operand instance based on the custom resource
     *
     * @param customResource custom resource
     * @param context current context
     */
    void delete(T customResource, Context<T> context);

    /**
     *
     * @param customResource custom resource
     * @return if the operand instance is still installing
     */
    boolean isInstalling(T customResource);

    /**
     * @param customResource custom resource
     * @return if the operand instance is ready to be used
     */
    boolean isReady(T customResource);

    /**
     * @param customResource custom resource
     * @return if the operand instance is in an error state
     */
    boolean isError(T customResource);

    /**
     *
     * @param customResource custom resource
     * @return if the operand instance is deleted
     */
    boolean isDeleted(T customResource);


    default Affinity kafkaPodAffinity(ManagedKafka managedKafka) {
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
