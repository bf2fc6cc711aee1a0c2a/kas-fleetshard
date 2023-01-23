package org.bf2.sync;

import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.informer.LocalLookup;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Objects;

@ApplicationScoped
public class ManagedKafkaAgentSync {

    @Inject
    Logger log;

    @Inject
    ManagedKafkaAgentResourceClient agentClient;

    @Inject
    LocalLookup lookup;

    @Inject
    ControlPlane controlPlane;

    @Timed(value = "sync.poll", extraTags = {"resource", "ManagedKafkaAgent"}, description = "The time spent processing polling calls")
    @Counted(value = "sync.poll", extraTags = {"resource", "ManagedKafkaAgent"}, description = "The number of polling calls")
    @Scheduled(every = "{poll.interval}", delayed = "{poll.delay}", concurrentExecution = ConcurrentExecution.SKIP)
    void loop() {
        ManagedKafkaAgent managedKafkaAgent = controlPlane.getManagedKafkaAgent();
        Objects.requireNonNull(managedKafkaAgent);
        createOrUpdateManagedKafkaAgent(managedKafkaAgent);
    }

    private void createOrUpdateManagedKafkaAgent(ManagedKafkaAgent remoteAgent) {
        ManagedKafkaAgent resource = lookup.getLocalManagedKafkaAgent();
        if (resource == null) {
            // the informer may not have run yet, so check more definitively
            resource = this.agentClient.getByName(this.agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
        }

        if (resource == null) {
            remoteAgent.getMetadata().setNamespace(agentClient.getNamespace());
            remoteAgent.getMetadata().setName(ManagedKafkaAgentResourceClient.RESOURCE_NAME);
            this.agentClient.create(remoteAgent);
            log.infof("ManagedKafkaAgent CR created");
        } else if (!remoteAgent.getSpec().equals(resource.getSpec())) {
            this.agentClient.edit(this.agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME, mka -> {
                mka.setSpec(remoteAgent.getSpec());
                return mka;
            });
            log.infof("ManagedKafkaAgent CR updated");
        }
    }

}
