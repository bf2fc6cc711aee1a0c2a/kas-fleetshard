package org.bf2.sync.controlplane;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.bf2.common.ConditionUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition.Type;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.VersionsBuilder;
import org.bf2.sync.ManagedKafkaAgentSync;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.Quantity;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
@UnlessBuildProfile("prod")
@Path(ControlPlaneApi.BASE_PATH)
public class MockControlPlane implements ControlPlaneApi {

    private final int MAX_KAFKA = 3;

    private static final String CERT = "cert";

    @Inject
    Logger log;

    @ConfigProperty(name="sync.run-control-plane-simulation", defaultValue = "false")
    boolean runSimulation;

    // current active clusters
    Map<String, ManagedKafka> kafkas = Collections.synchronizedMap(new HashMap<>());

    @Inject
    ManagedKafkaAgentSync agentSync;

    volatile ManagedKafkaAgent agent;

    @PostConstruct
    void initAgent() {
        agent = agentSync.createAgentFromConfig();
    }

    // Unique Id for the clusters
    private AtomicInteger clusterIdGenerator = new AtomicInteger(1);

    private ManagedKafka createManagedKafka(int id) {
        ManagedKafka mk = new ManagedKafka();
        mk.setSpec(new ManagedKafkaSpecBuilder()
                .withVersions(new VersionsBuilder().withKafka("2.2.6").withStrimzi("0.21.1").build())
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
                    .withBootstrapServerHost("xyz.com")
                    .withNewTls()
                        .withCert(CERT)
                        .withKey(CERT)
                    .endTls()
                .endEndpoint()
                .build());
        mk.setId(clusterName(id));
        mk.setPlacementId(UUID.randomUUID().toString());
        mk.getMetadata().setName("kluster-"+clusterName(id));
        return mk;
    }

    private String clusterName(int i) {
        return "user-"+i;
    }

    @Scheduled(every = "{poll.interval}")
    void loop() {

        // only run simulation when needed
        if (!this.runSimulation) {
            return;
        }

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
        if (status == null || status.getConditions() == null) {
            return false;
        }
        return ConditionUtils.findManagedKafkaCondition(status.getConditions(), Type.Deleted)
                .filter(c -> "True".equals(c.getStatus())).isPresent();
    }

    @Override
    public void updateStatus(@PathParam("id") String id, ManagedKafkaAgentStatus status){
        log.infof("control plane::updateAgentStatus (capacity) <- Received %s", status);
    }

    @Override
    public List<ManagedKafka> getKafkaClusters(String id) {
        log.info("control plane::getKafkaClusters <- Received");
        return new ArrayList<ManagedKafka>(kafkas.values());
    }

    @Override
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

    @Override
    public ManagedKafkaAgent get(String id) {
        return agent;
    }

    @POST
    @Path("/{id}/kafkas")
    @Produces(MediaType.APPLICATION_JSON)
    public void createCluster(ManagedKafka mk) {
        this.kafkas.put(mk.getId(), mk);
        log.infof("control plane:: Received request to create/update ManagedKafka %s", mk.getId());
    }

    @DELETE
    @Path("/{id}/kafkas/{clusterid}")
    @Produces(MediaType.APPLICATION_JSON)
    public void deleteCluster(@PathParam("clusterid") String clusterId) {
        log.infof("control plane:: received request to delete ManagedKafka %s", clusterId);
        markForDeletion(clusterId);
    }

    @PUT
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public void createAgent(ManagedKafkaAgent agent) {
        log.infof("control plane:: Received request to create agent %s", agent);
        this.agent = agent;
    }

}
