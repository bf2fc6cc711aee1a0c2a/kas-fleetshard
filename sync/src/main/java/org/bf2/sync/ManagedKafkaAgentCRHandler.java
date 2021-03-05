package org.bf2.sync;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.quarkus.scheduler.Scheduled;

import org.bf2.common.AgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfigurationBuilder;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * TODO: This is throw away code until the Control Plane API for ManagedkafkaAgent CR is defined.
 */
@ApplicationScoped
public class ManagedKafkaAgentCRHandler {

    private static final String STRIMZI_VERSIONS = "strimzi.allowed_versions";

    @Inject
    Logger log;

    @Inject
    AgentResourceClient agentClient;

    @Inject
    LocalLookup lookup;

    @ConfigProperty(name = "cluster.id", defaultValue = "testing")
    String clusterId;

    @ConfigProperty(name=STRIMZI_VERSIONS)
    String strimziVersions;

    @ConfigProperty(name = "observability.access_token")
    String accessToken;

    @ConfigProperty(name = "observability.channel")
    String channel;

    @ConfigProperty(name = "observability.repository")
    String repository;

    @Scheduled(every = "60s")
    void loop() {
        createOrUpdateManagedKafkaAgentCR();
    }

    private void createOrUpdateManagedKafkaAgentCR() {

        ManagedKafkaAgent resource = lookup.getLocalManagedKafkaAgent();

        // allowed Strimzi versions by the control plane
        String[] allowedVersions = new String[] {};
        if (this.strimziVersions != null && this.strimziVersions.trim().length() > 0) {
            allowedVersions = this.strimziVersions.trim().split("\\s*,\\s*");
            if (allowedVersions.length == 0) {
                log.errorf("The property %s is not configured", STRIMZI_VERSIONS);
                return;
            }
        }

        // Observability repository information
        ObservabilityConfiguration observabilityConfig = new ObservabilityConfigurationBuilder()
                .withAccessToken(this.accessToken)
                .withChannel(this.channel)
                .withRepository(this.repository)
                .build();

        ManagedKafkaAgent newResource = new ManagedKafkaAgentBuilder()
                .withSpec(new ManagedKafkaAgentSpecBuilder()
                        .withClusterId(this.clusterId)
                        .withAllowedStrimziVersions(allowedVersions)
                        .withObservability(observabilityConfig)
                        .build())
                .withMetadata(new ObjectMetaBuilder().withName(AgentResourceClient.RESOURCE_NAME)
                        .withNamespace(agentClient.getNamespace())
                        .build())
                .build();

        if (!newResource.getSpec().equals(resource.getSpec())) {
            this.agentClient.createOrReplace(resource);
            log.infof("ManagedKafkaAgent CR updated for allowed strmzi version with name %s", AgentResourceClient.RESOURCE_NAME);
        }
    }
}