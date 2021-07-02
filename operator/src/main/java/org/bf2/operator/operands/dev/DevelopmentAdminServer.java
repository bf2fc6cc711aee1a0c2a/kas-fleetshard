
package org.bf2.operator.operands.dev;

import io.fabric8.kubernetes.api.model.Container;
import io.quarkus.arc.properties.IfBuildProperty;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Provides same functionalities to get a AdminServer deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@IfBuildProperty(name = "adminserver", stringValue = "dev")
public class DevelopmentAdminServer extends org.bf2.operator.operands.AdminServer {

    /* test */
    @Override
    protected List<Container> buildContainers(ManagedKafka managedKafka) {
        var containers = super.buildContainers(managedKafka);

        containers.forEach(container -> {
            container.setImagePullPolicy("Always");
        });

        return containers;
    }

}
