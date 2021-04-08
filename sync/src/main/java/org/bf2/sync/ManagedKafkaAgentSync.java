package org.bf2.sync;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.bf2.common.AgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.informer.LocalLookup;
import org.jboss.logging.Logger;

import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ManagedKafkaAgentSync {

    @Inject
    Logger log;

    @Inject
    AgentResourceClient agentClient;

    @Inject
    LocalLookup lookup;

    @Inject
    ControlPlane controlPlane;

    @Timed(value = "sync.poll", extraTags = {"resource", "ManagedKafkaAgent"}, description = "The time spent processing polling calls")
    @Counted(value = "sync.poll", extraTags = {"resource", "ManagedKafkaAgent"}, description = "The number of polling calls")
    @Scheduled(every = "{poll.interval}", delayed = "5s")
    void loop() {
        if (!lookup.isReady()) {
            log.debug("Not ready to poll, the lookup is not ready");
            return;
        }
        ManagedKafkaAgent managedKafkaAgent = controlPlane.getManagedKafkaAgent();
        createOrUpdateManagedKafkaAgent(managedKafkaAgent);
    }

    private void createOrUpdateManagedKafkaAgent(ManagedKafkaAgent remoteAgent) {
        ManagedKafkaAgent resource = lookup.getLocalManagedKafkaAgent();
        if (resource == null) {
            // the informer may not have run yet, so check more definitively
            resource = this.agentClient.getByName(this.agentClient.getNamespace(), AgentResourceClient.RESOURCE_NAME);
        }

        if (resource == null) {
            remoteAgent.getMetadata().setNamespace(agentClient.getNamespace());
            remoteAgent.getMetadata().setName(AgentResourceClient.RESOURCE_NAME);
            this.agentClient.create(remoteAgent);
            log.infof("ManagedKafkaAgent CR created");
        } else if (remoteAgent.getSpec() != null && !remoteAgent.getSpec().equals(resource.getSpec())) {
            this.agentClient.edit(this.agentClient.getNamespace(), AgentResourceClient.RESOURCE_NAME, mka -> {
                mka.setSpec(remoteAgent.getSpec());
                return mka;
            });
            log.infof("ManagedKafkaAgent CR updated");
        }
    }

}
