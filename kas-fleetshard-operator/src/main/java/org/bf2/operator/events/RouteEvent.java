package org.bf2.operator.events;

import io.fabric8.openshift.api.model.Route;
import io.javaoperatorsdk.operator.processing.event.AbstractEvent;

public class RouteEvent extends AbstractEvent {

    private Route route;

    public RouteEvent(Route route, RouteEventSource routeEventSource) {
        super(route.getMetadata().getOwnerReferences().get(0).getUid(), routeEventSource);
        this.route = route;
    }

    public Route getRoute() {
        return route;
    }
}
