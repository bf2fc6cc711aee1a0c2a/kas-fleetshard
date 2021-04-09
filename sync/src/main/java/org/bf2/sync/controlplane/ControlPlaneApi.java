package org.bf2.sync.controlplane;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import java.util.Map;

public interface ControlPlaneApi {

    public static final String BASE_PATH = "/api/managed-services-api/v1/agent-clusters/";

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    ManagedKafkaAgent get(@PathParam("id") String id);

    @PUT
    @Path("/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    void updateStatus(@PathParam("id") String id, ManagedKafkaAgentStatus status);

    @GET
    @Path("/{id}/kafkas")
    @Produces(MediaType.APPLICATION_JSON)
    ManagedKafkaList getKafkaClusters(@PathParam("id") String id);

    @PUT
    @Path("/{id}/kafkas/status")
    @Consumes(MediaType.APPLICATION_JSON)
    void updateKafkaClustersStatus(@PathParam("id") String id, Map<String, ManagedKafkaStatus> status);

}
