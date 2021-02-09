package org.bf2.sync.controlplane;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.VersionsBuilder;
import org.bf2.sync.ManagedKafkaSync;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.Quantity;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
@Path("/api/managed-services-api/v1/agent-clusters/")
public class MockControlPlane {

    private final int MAX_KAFKA = 3;

    private static final String CERT = "      -----BEGIN CERTIFICATE-----\n"
            + "      MIICLDCCAdKgAwIBAgIBADAKBggqhkjOPQQDAjB9MQswCQYDVQQGEwJCRTEPMA0G\n"
            + "      A1UEChMGR251VExTMSUwIwYDVQQLExxHbnVUTFMgY2VydGlmaWNhdGUgYXV0aG9y\n"
            + "      aXR5MQ8wDQYDVQQIEwZMZXV2ZW4xJTAjBgNVBAMTHEdudVRMUyBjZXJ0aWZpY2F0\n"
            + "      ZSBhdXRob3JpdHkwHhcNMTEwNTIzMjAzODIxWhcNMTIxMjIyMDc0MTUxWjB9MQsw\n"
            + "      CQYDVQQGEwJCRTEPMA0GA1UEChMGR251VExTMSUwIwYDVQQLExxHbnVUTFMgY2Vy\n"
            + "      dGlmaWNhdGUgYXV0aG9yaXR5MQ8wDQYDVQQIEwZMZXV2ZW4xJTAjBgNVBAMTHEdu\n"
            + "      dVRMUyBjZXJ0aWZpY2F0ZSBhdXRob3JpdHkwWTATBgcqhkjOPQIBBggqhkjOPQMB\n"
            + "      BwNCAARS2I0jiuNn14Y2sSALCX3IybqiIJUvxUpj+oNfzngvj/Niyv2394BWnW4X\n"
            + "      uQ4RTEiywK87WRcWMGgJB5kX/t2no0MwQTAPBgNVHRMBAf8EBTADAQH/MA8GA1Ud\n"
            + "      DwEB/wQFAwMHBgAwHQYDVR0OBBYEFPC0gf6YEr+1KLlkQAPLzB9mTigDMAoGCCqG\n"
            + "      SM49BAMCA0gAMEUCIDGuwD1KPyG+hRf88MeyMQcqOFZD0TbVleF+UsAGQ4enAiEA\n"
            + "      l4wOuDwKQa+upc8GftXE2C//4mKANBC6It01gUaTIpo=\n"
            + "      -----END CERTIFICATE-----";

    @Inject
    Logger log;

    // current active clusters
    Map<String, ManagedKafka> kafkas = Collections.synchronizedMap(new HashMap<>());

    // Unique Id for the clusters
    private AtomicInteger clusterIdGenerator = new AtomicInteger(1);

    private ManagedKafka createManagedKafka(int id) {
        ManagedKafka mk = new ManagedKafka();
        mk.setSpec(new ManagedKafkaSpecBuilder()
                .withVersions(new VersionsBuilder().withKafka("2.2.6").build())
                .withNewCapacity()
                    .withIngressEgressThroughputPerSec(Quantity.parse("2Mi"))
                    .withTotalMaxConnections(100)
                    .withMaxDataRetentionPeriod("P14D")
                    .withMaxDataRetentionSize(Quantity.parse("50Gi"))
                    .withMaxPartitions(100)
                .endCapacity()
                .withNewOauth()
                    .withClientId("clientId")
                    .withClientSecret("secret")
                    .withUserNameClaim("claim")
                    .withJwksEndpointURI("http://jwks")
                    .withTokenEndpointURI("https://token")
                    .withValidIssuerEndpointURI("http://issuer")
                    .withUserNameClaim("claim")
                    .withTlsTrustedCertificate(CERT)
                .endOauth()
                .withNewEndpoint()
                    .withBootstrapAddress("xyz.com")
                    .withNewTls()
                        .withCert(CERT)
                        .withKey(CERT)
                    .endTls()
                .endEndpoint()
                .build());
        mk.setId(clusterName(id));
        mk.getMetadata().setName(clusterName(id));
        //mk.getMetadata().setNamespace(clusterName(id));


        return mk;
    }

    private String clusterName(int i) {
        return "user-"+i;
    }

    @Scheduled(every = "{poll.interval}")
    void loop() {
        Random random = new Random(System.currentTimeMillis());

        log.info("control plane:: Running Simulation");

        // feed the start of clusters
        if (this.kafkas.size() == 0) {
            int max = Math.abs(random.nextInt(MAX_KAFKA));
            for (int i = 0; i < max; i++) {
                ManagedKafka k = createManagedKafka(this.clusterIdGenerator.getAndIncrement());
                log.infof("control plane::marking %s for addition", k.getId());
                this.kafkas.put(k.getId(), k);
            }
        }

        // delete a instance by random
        if (this.kafkas.size() > 1 && random.nextBoolean()) {
            int idx = Math.abs(random.nextInt(this.kafkas.size()));
            int i = 0;
            for (ManagedKafka k:kafkas.values()) {
                if (i++ < idx) {
                    continue;
                } else {
                    markForDeletion(k.getId());
                    break;
                }
            }
        }

        // selectively add
        if (this.kafkas.size() < MAX_KAFKA && random.nextBoolean()) {
            ManagedKafka k = createManagedKafka(this.clusterIdGenerator.getAndIncrement());
            log.infof("control plane:: creating a new cluster %s ", k.getId());
            this.kafkas.put(k.getId(), k);
        }

        log.info("--------------------------------------------------");
        for(ManagedKafka mk:this.kafkas.values()) {
            log.infof("ManagedKafka: %s, delete requested: %s", mk.getId(), mk.getSpec().isDeleted());
        }
        log.info("--------------------------------------------------");
    }

    private void markForDeletion(String id) {
        ManagedKafka mk = this.kafkas.get(id);
        if (mk != null && !mk.isMarkedForDeletion()) {
            log.infof("control plane:: marking cluster %s for deletion", mk.getId());
            mk.getSpec().setDeleted(true);
        } else {
            log.infof("control plane:: Is cluster %s already deleted?", id);
        }
    }

    private boolean isDeleted(ManagedKafkaStatus status) {
        for (ManagedKafkaCondition c : status.getConditions()) {
            if (c.getType().equals(ManagedKafkaSync.INSTANCE_DELETION_COMPLETE)) {
                return true;
            }
        }
        return false;
    }

    @PUT
    @Path("/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    public void updateStatus(@PathParam("id") String id, ManagedKafkaAgentStatus status){
        log.info("control plane::updateAgentStatus (capacity) <- Received");
        log.info(status);
    }

    @GET
    @Path("/{id}/kafkas")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ManagedKafka> getKafkaClusters(String id) {
        log.info("control plane::getKafkaClusters <- Received");
        return new ArrayList<ManagedKafka>(kafkas.values());
    }

    @PUT
    @Path("/{id}/kafkas/status")
    @Consumes(MediaType.APPLICATION_JSON)
    public void updateKafkaClustersStatus(@PathParam(value = "id") String id, Map<String, ManagedKafkaStatus> statusMap) {
        log.infof("control plane:: updateKafkaClustersStatus <- Received from cluster %s, %s", id, statusMap);

        // clean up the deleted
        statusMap.forEach((k, v) -> {
            log.infof("control plane:: Status of %s received", k);
            ManagedKafka mk = this.kafkas.get(k);
            if (mk != null) {
                if (mk.getSpec().isDeleted() && isDeleted(v)) {
                    log.infof("control plane:: Removing cluster %s as it is deleted", mk.getId());
                    this.kafkas.remove(k);
                }
            }
        });
    }

    @POST
    @Path("/{id}/kafkas/create")
    @Produces(MediaType.APPLICATION_JSON)
    public ManagedKafka createCluster() {
        ManagedKafka mk = createManagedKafka(clusterIdGenerator.getAndIncrement());
        this.kafkas.put(mk.getId(), mk);
        log.infof("control plane:: Received request to create a new client %s", mk.getId());
        return mk;
    }

    @DELETE
    @Path("/{id}/kafkas/delete/{clausterid}")
    @Produces(MediaType.APPLICATION_JSON)
    public void deleteCluster(@PathParam("clausterid") String clusterId) {
        log.infof("control plane:: received request to delete client %s", clusterId);
        markForDeletion(clusterId);
    }
}
