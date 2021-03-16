package org.bf2.operator.sync;

import io.fabric8.kubernetes.api.model.Quantity;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import org.bf2.common.AbstractCustomResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.VersionsBuilder;
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
public class MockSync {

    private static final String CERT = "cert";

    @Inject
    Logger log;

    @Inject
    ManagedKafkaResourceClient mkClient;

    int maxKafkas = 3;

    // current active clusters
    Map<String, ManagedKafka> kafkas = new ConcurrentHashMap<>();

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

    @Scheduled(every = "15s", delayed = "10s")
    void loop() {

        Random random = new Random(System.currentTimeMillis());
        log.info("Mock Sync:: Running Simulation");

        // feed the start of clusters
        if (this.kafkas.size() == 0) {
            int max = Math.abs(random.nextInt(maxKafkas));
            for (int i = 0; i < max; i++) {
                ManagedKafka k = createManagedKafka(this.clusterIdGenerator.getAndIncrement());
                log.infof("Mock Sync::marking %s for addition", k.getId());
                this.kafkas.put(k.getId(), k);
                mkClient.create(k);
            }
        }
    }
}
