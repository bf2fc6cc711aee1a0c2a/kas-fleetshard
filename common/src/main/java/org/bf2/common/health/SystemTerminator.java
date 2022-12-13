package org.bf2.common.health;

import io.quarkus.runtime.Quarkus;
import org.bf2.common.ResourceInformerFactory;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class SystemTerminator {

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    @Inject
    Logger log;

    public void notifyUnhealthy() {
        boolean watching = resourceInformerFactory.allInformersWatching();
        log.errorf("The instance has is in an unhealthy state, initiating restart - watching {}", watching);
        Quarkus.asyncExit();
    }

}
