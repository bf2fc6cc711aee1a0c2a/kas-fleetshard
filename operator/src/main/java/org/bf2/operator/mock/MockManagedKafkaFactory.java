package org.bf2.operator.mock;

import io.fabric8.kubernetes.api.model.Quantity;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.VersionsBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
@IfBuildProperty(name = "kafka", stringValue = "test")
public class MockManagedKafkaFactory {

    private static final String CERT = "cert";

    @Inject
    Logger log;

    @Inject
    ManagedKafkaResourceClient mkClient;

    @ConfigProperty(name="mock.factory.max-managedkafka", defaultValue = "3")
    int maxKafkas;

    // current active clusters
    Map<String, ManagedKafka> kafkas = new ConcurrentHashMap<>();

    // Unique Id for the clusters
    private AtomicInteger clusterIdGenerator = new AtomicInteger(1);

    private ManagedKafka createManagedKafka(int id) {
        ManagedKafka mk = new ManagedKafka();
        mk.setSpec(new ManagedKafkaSpecBuilder()
                .withVersions(new VersionsBuilder().withKafka("2.6.0").withStrimzi("0.21.1").build())
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
        mk.getMetadata().setName("kluster-" + clusterName(id));
        return mk;
    }

    private String clusterName(int i) {
        return "kafka-" + i;
    }

    @Scheduled(every = "{mock.factory.interval}", delayed = "10s")
    void loop() {

        Random random = new Random(System.currentTimeMillis());
        log.info("Mock ManagedKafka Factory:: Running Simulation");

        // feed the start of clusters
        if (this.kafkas.size() == 0) {
            int max = Math.abs(random.nextInt(maxKafkas));
            for (int i = 0; i < max; i++) {
                ManagedKafka k = createManagedKafka(this.clusterIdGenerator.getAndIncrement());
                log.infof("Mock ManagedKafka Factory::marking %s for addition", k.getId());
                this.kafkas.put(k.getId(), k);
                mkClient.create(k);
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
        if (this.kafkas.size() < maxKafkas && random.nextBoolean()) {
            ManagedKafka k = createManagedKafka(this.clusterIdGenerator.getAndIncrement());
            log.infof("Mock ManagedKafka Factory:: creating a new cluster %s ", k.getId());
            this.kafkas.put(k.getId(), k);
            mkClient.create(k);
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
            log.infof("Mock ManagedKafka Factory:: marking cluster %s for deletion", mk.getId());
            mk.getSpec().setDeleted(true);
            mkClient.patch(mk);
        } else {
            log.infof("Mock ManagedKafka Factory:: Is cluster %s already deleted?", id);
        }
    }
}
