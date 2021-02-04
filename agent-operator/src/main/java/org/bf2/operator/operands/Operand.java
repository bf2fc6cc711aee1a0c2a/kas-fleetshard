package org.bf2.operator.operands;

import io.fabric8.kubernetes.client.CustomResource;
import io.javaoperatorsdk.operator.api.Context;

/**
 * Define common behaviour across operands related to a controller handling a specific custom resource
 * @param <T> custom resource type handled by corresponding controller
 */
public interface Operand<T extends CustomResource> {

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
}
