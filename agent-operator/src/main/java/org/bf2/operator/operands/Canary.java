package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
public class Canary {

    public static Deployment create(ManagedKafka managedKafka) {
        // TODO: generate the canary deployment
        return new DeploymentBuilder().build();
    }

    public static boolean isInstalling(DeploymentStatus status) {
        // TODO: logic for check if it's installing
        return false;
    }

    public static boolean isReady(DeploymentStatus status) {
        // TODO: logic for check if it's ready
        return true;
    }

    public static boolean isError(DeploymentStatus status) {
        // TODO: logic for check if it's error
        return false;
    }
}
