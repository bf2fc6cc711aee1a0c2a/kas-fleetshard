package org.bf2.sync;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.scheduler.Scheduled;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfigurationBuilder;
import org.bf2.sync.informer.LocalLookup;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Arrays;

/**
 * TODO: This is throw away code until the Control Plane API for ManagedkafkaAgent CR is defined.
 */
@ApplicationScoped
public class ManagedKafkaAgentCRHandler {

    private static final String RESOURCE_NAME = "managed-agent";
    private static final String STRIMZI_VERSIONS = "strimzi.allowed_versions";

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubeClient;

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
        String namespace = this.kubeClient.getNamespace();

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

        boolean update = false;
        if (resource == null) {
            resource = new ManagedKafkaAgentBuilder()
                    .withSpec(new ManagedKafkaAgentSpecBuilder()
                            .withClusterId(this.clusterId)
                            .withAllowedStrimziVersions(allowedVersions)
                            .withObservability(observabilityConfig)
                            .build())
                    .withMetadata(new ObjectMetaBuilder().withName(RESOURCE_NAME)
                            .withNamespace(namespace)
                            .build())
                    .build();
            update = true;
        }

        if (!Arrays.equals(resource.getSpec().getAllowedStrimziVersions(), allowedVersions)) {
            resource.getSpec().setAllowedStrimziVersions(allowedVersions);
            update = true;
        }

        if (!resource.getSpec().getObservability().equals(observabilityConfig)) {
            resource.getSpec().setObservability(observabilityConfig);
            update = true;
        }

        if (update) {
            var agentClient = kubeClient.customResources(ManagedKafkaAgent.class, ManagedKafkaAgentList.class);
            agentClient.inNamespace(namespace).createOrReplace(resource);
            log.infof("ManagedKafkaAgent CR updated for allowed strmzi version with name %s", RESOURCE_NAME);
        }
    }
}