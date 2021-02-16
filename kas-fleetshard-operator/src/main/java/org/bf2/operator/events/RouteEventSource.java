package org.bf2.operator.events;

import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.openshift.api.model.Route;
import io.javaoperatorsdk.operator.processing.event.AbstractEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RouteEventSource extends AbstractEventSource implements ResourceEventHandler<Route> {

    private static final Logger log = LoggerFactory.getLogger(RouteEventSource.class);

    @Override
    public void onAdd(Route route) {
        log.info("Add event received for Route {}/{}", route.getMetadata().getNamespace(), route.getMetadata().getName());
        handleEvent(route);
    }

    @Override
    public void onUpdate(Route oldRoute, Route newRoute) {
        log.info("Update event received for Route {}/{}", oldRoute.getMetadata().getNamespace(), oldRoute.getMetadata().getName());
        handleEvent(newRoute);
    }

    @Override
    public void onDelete(Route route, boolean deletedFinalStateUnknown) {
        log.info("Delete event received for Route {}/{}", route.getMetadata().getNamespace(), route.getMetadata().getName());
        handleEvent(route);
    }

    private void handleEvent(Route route) {
        eventHandler.handleEvent(new RouteEvent(route, this));
    }
}
