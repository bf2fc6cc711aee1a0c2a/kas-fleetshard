package org.bf2.operator.operands;

import io.fabric8.kubernetes.client.CustomResource;
import io.javaoperatorsdk.operator.api.Context;

import java.util.Collections;
import java.util.List;

/**
 * Define common behaviour across operands related to a controller handling a specific custom resource
 * @param <T> custom resource type handled by corresponding controller
 * @param <C> custom resource condition type to report into the status as result of operand validation
 */
public interface Operand<T extends CustomResource<?, ?>, C> {

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
     * @param customResource custom resource
     * @return if the operand instance is deleted
     */
    boolean isDeleted(T customResource);

    /**
     * Validate the part of the custom resource that belongs to the current operand
     * and return a list of corresponding warning conditions
     *
     * @param customResource custom resource
     * @return list of result conditions from the validation
     */
    default List<C> validate(T customResource) {
        return Collections.emptyList();
    }
}
