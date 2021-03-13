package org.bf2.sync;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.quarkus.scheduler.Scheduled;

import org.bf2.common.AgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfigurationBuilder;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.WebApplicationException;

/**
 * TODO: This is throw away code until the Control Plane API for ManagedkafkaAgent CR is defined.
 */
@ApplicationScoped
public class ManagedKafkaAgentSync {

    @Inject
    Logger log;

    @Inject
    AgentResourceClient agentClient;

    @Inject
    LocalLookup lookup;

    @ConfigProperty(name = "observability.access_token")
    String accessToken;

    @ConfigProperty(name = "observability.channel")
    String channel;

    @ConfigProperty(name = "observability.repository")
    String repository;

    @Inject
    ControlPlane controlPlane;

    @Scheduled(every = "{poll.interval}")
    void loop() {
        ManagedKafkaAgent managedKafkaAgent = null;
        try {
            managedKafkaAgent = controlPlane.getManagedKafkaAgent();
            // we're assuming non-null from the control plane
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() != HttpURLConnection.HTTP_NOT_FOUND) {
                throw e;
            }
            managedKafkaAgent = createAgentFromConfig();
        }
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

    public ManagedKafkaAgent createAgentFromConfig() {
        // Observability repository information
        ObservabilityConfiguration observabilityConfig = new ObservabilityConfigurationBuilder()
                .withAccessToken(this.accessToken)
                .withChannel(this.channel)
                .withRepository(this.repository)
                .build();

        return new ManagedKafkaAgentBuilder()
                .withSpec(new ManagedKafkaAgentSpecBuilder()
                        .withObservability(observabilityConfig)
                        .build())
                .withMetadata(new ObjectMetaBuilder().withName(AgentResourceClient.RESOURCE_NAME)
                        .withNamespace(agentClient.getNamespace())
                        .build())
                .build();
    }
}