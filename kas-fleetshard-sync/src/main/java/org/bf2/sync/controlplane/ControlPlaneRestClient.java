package org.bf2.sync.controlplane;

import java.util.List;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import io.smallrye.mutiny.Uni;

@ApplicationScoped
@Path("/api/managed-services-api/v1/agent-clusters/")
@RegisterRestClient
public interface ControlPlaneRestClient {

    @PUT
    @Path("/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<Void> updateStatus(@PathParam("id") String id, ManagedKafkaAgentStatus status);

    @GET
    @Path("/{id}/kafkas")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<List<ManagedKafka>> getKafkaClusters(@PathParam("id") String id);

    @PUT
    @Path("/{id}/kafkas/status")
    @Consumes(MediaType.APPLICATION_JSON)
    Uni<Void> updateKafkaClustersStatus(@PathParam("id") String id, Map<String, ManagedKafkaStatus> status);

}
