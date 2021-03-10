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

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * TODO: This is throw away code until the Control Plane API for ManagedkafkaAgent CR is defined.
 */
@ApplicationScoped
public class ManagedKafkaAgentSync {

    private static final String STRIMZI_VERSIONS = "strimzi.allowed_versions";

    @Inject
    Logger log;

    @Inject
    AgentResourceClient agentClient;

    @Inject
    LocalLookup lookup;

    @ConfigProperty(name=STRIMZI_VERSIONS)
    String strimziVersions;

    @ConfigProperty(name = "observability.access_token")
    String accessToken;

    @ConfigProperty(name = "observability.channel")
    String channel;

    @ConfigProperty(name = "observability.repository")
    String repository;

    @ConfigProperty(name = "control-plane.agent-spec-enabled")
    boolean specEnabled;

    @Inject
    ControlPlane controlPlane;

    @Scheduled(every = "{poll.interval}")
    void loop() {
        if (specEnabled) {
            createOrUpdateManagedKafkaAgent(controlPlane.getManagedKafkaAgent());
        } else {
            createOrUpdateManagedKafkaAgentCRFromSecret();
        }
    }

    void setSpecEnabled(boolean specEnabled) {
        this.specEnabled = specEnabled;
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

    private void createOrUpdateManagedKafkaAgentCRFromSecret() {
        ManagedKafkaAgent newResource = createAgentFromConfig();

        createOrUpdateManagedKafkaAgent(newResource);
    }

    public ManagedKafkaAgent createAgentFromConfig() {
        // allowed Strimzi versions by the control plane
        String[] allowedVersions = new String[] {};
        if (this.strimziVersions != null && this.strimziVersions.trim().length() > 0) {
            allowedVersions = this.strimziVersions.trim().split("\\s*,\\s*");
            if (allowedVersions.length == 0) {
                log.errorf("The property %s is not configured", STRIMZI_VERSIONS);
            }
        }

        // Observability repository information
        ObservabilityConfiguration observabilityConfig = new ObservabilityConfigurationBuilder()
                .withAccessToken(this.accessToken)
                .withChannel(this.channel)
                .withRepository(this.repository)
                .build();

        return new ManagedKafkaAgentBuilder()
                .withSpec(new ManagedKafkaAgentSpecBuilder()
                        .withAllowedStrimziVersions(allowedVersions)
                        .withObservability(observabilityConfig)
                        .build())
                .withMetadata(new ObjectMetaBuilder().withName(AgentResourceClient.RESOURCE_NAME)
                        .withNamespace(agentClient.getNamespace())
                        .build())
                .build();
    }
}