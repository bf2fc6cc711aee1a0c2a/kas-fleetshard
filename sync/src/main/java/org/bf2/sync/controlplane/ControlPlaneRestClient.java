package org.bf2.sync.controlplane;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Path;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@ApplicationScoped
@RegisterRestClient(configKey = "control-plane")
@Path(ControlPlaneApi.BASE_PATH)
public interface ControlPlaneRestClient extends ControlPlaneApi {

}
