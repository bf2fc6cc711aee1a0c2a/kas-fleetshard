package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Reason;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Status;

import java.util.Optional;

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
    void delete(T customResource, Context context);

    /**
     *
     * @param customResource custom resource
     * @return if the operand instance is deleted
     */
    boolean isDeleted(T customResource);

    /**
     * Get the readiness for this component
     * @param customResource
     * @return the readiness information, never null
     */
    OperandReadiness getReadiness(T customResource);

    static OperandReadiness getDeploymentReadiness(Deployment deployment, String name) {
        if (deployment == null) {
            return new OperandReadiness(Status.False, Reason.Installing, String.format("Deployment %s does not exist", name));
        }
        if (Readiness.isDeploymentReady(deployment)) {
            return new OperandReadiness(Status.True, null, null);
        }
        return Optional.ofNullable(deployment.getStatus())
                .map(DeploymentStatus::getConditions)
                .flatMap(l -> l.stream()
                        .filter(c -> "Progressing".equals(c.getType()))
                        .findAny()
                        .map(dc -> new OperandReadiness(Status.False,
                                "True".equals(dc.getStatus()) ? Reason.Installing : Reason.Error,
                                dc.getMessage())))
                .orElseGet(() -> new OperandReadiness(Status.False, Reason.Installing, String
                        .format("Deployment %s has no progressing condition", deployment.getMetadata().getName())));
    }
}
