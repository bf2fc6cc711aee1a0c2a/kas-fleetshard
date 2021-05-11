
package org.bf2.operator.operands.dev;

import io.fabric8.kubernetes.api.model.Container;
import io.quarkus.arc.properties.IfBuildProperty;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@IfBuildProperty(name = "canary", stringValue = "dev")
public class DevelopmentCanary extends org.bf2.operator.operands.Canary {

    /* test */
    @Override
    protected List<Container> getContainers(ManagedKafka managedKafka) {
        var containers = super.getContainers(managedKafka);

        containers.forEach(container -> {
            container.setImagePullPolicy("Always");
        });

        return containers;
    }

}
