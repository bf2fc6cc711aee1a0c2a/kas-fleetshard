package org.bf2.sync.controlplane;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Path;

@ApplicationScoped
@RegisterRestClient(configKey = "control-plane")
@Path(ControlPlaneApi.BASE_PATH)
public interface ControlPlaneRestClient extends ControlPlaneApi {

}
