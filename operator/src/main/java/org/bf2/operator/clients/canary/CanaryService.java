package org.bf2.operator.clients.canary;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import java.io.Closeable;

public interface CanaryService extends Closeable {

    @GET
    @Path("/status")
    Status getStatus();
}
