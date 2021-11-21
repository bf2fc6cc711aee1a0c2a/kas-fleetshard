package org.bf2.operator.clients.canary;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

public interface CanaryService {

    @GET
    @Path("/status")
    Status getStatus();
}
