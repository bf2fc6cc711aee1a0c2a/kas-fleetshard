package org.bf2.operator.mock;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
@IfBuildProperty(name = "kafka", stringValue = "test")
public class MockManagedKafkaFactory {

    @Inject
    Logger log;

    @Inject
    ManagedKafkaResourceClient mkClient;

    @ConfigProperty(name = "mock.factory.max-managedkafka", defaultValue = "3")
    int maxKafkas;

    // current active clusters
    Map<String, ManagedKafka> kafkas = new ConcurrentHashMap<>();

    // Unique Id for the clusters
    private AtomicInteger clusterIdGenerator = new AtomicInteger(1);

    @Scheduled(every = "{mock.factory.interval}", delayed = "10s")
    void loop() {

        Random random = new Random(System.currentTimeMillis());
        log.info("Mock ManagedKafka Factory:: Running Simulation");

        // feed the start of clusters
        if (this.kafkas.size() == 0) {
            int max = Math.abs(random.nextInt(maxKafkas));
            for (int i = 0; i < max; i++) {
                ManagedKafka k = ManagedKafka.getDummyInstance(this.clusterIdGenerator.getAndIncrement());
                log.infof("Mock ManagedKafka Factory::marking %s for addition", k.getId());
                this.kafkas.put(k.getId(), k);
                mkClient.create(k);
            }
        }

        // delete a instance by random
        if (this.kafkas.size() > 1 && random.nextBoolean()) {
            int idx = Math.abs(random.nextInt(this.kafkas.size()));
            int i = 0;
            for (ManagedKafka k : kafkas.values()) {
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
            ManagedKafka k = ManagedKafka.getDummyInstance(this.clusterIdGenerator.getAndIncrement());
            log.infof("Mock ManagedKafka Factory:: creating a new cluster %s ", k.getId());
            this.kafkas.put(k.getId(), k);
            mkClient.create(k);
        }

        log.info("--------------------------------------------------");
        for (ManagedKafka mk : this.kafkas.values()) {
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
