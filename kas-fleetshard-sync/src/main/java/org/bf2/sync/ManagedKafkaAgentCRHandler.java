package org.bf2.sync;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
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

    @ConfigProperty(name = "cluster.id", defaultValue = "testing")
    private String clusterId;

    @ConfigProperty(name=STRIMZI_VERSIONS)
    String strimziVersions;

    private MixedOperation<ManagedKafkaAgent, ManagedKafkaAgentList, Resource<ManagedKafkaAgent>> agentClient;

    void onStart(@Observes StartupEvent ev) {
        if (shouldCreateManagedKafkaAgent()) {
            createOrUpdateManagedKafkaAgentCR();
        }
    }

    //must be running locally with no namespace configured
    private boolean shouldCreateManagedKafkaAgent() {
        return !(this.kubeClient.namespaces().withName(this.kubeClient.getNamespace()).get() == null);
    }

    @Scheduled(every = "60s")
    void loop() {
        if (shouldCreateManagedKafkaAgent()) {
            createOrUpdateManagedKafkaAgentCR();
        }
    }

    private void createOrUpdateManagedKafkaAgentCR() {
        String namespace = this.kubeClient.getNamespace();
        this.agentClient = kubeClient.customResources(ManagedKafkaAgent.class, ManagedKafkaAgentList.class);

        ManagedKafkaAgent resource = this.agentClient.inNamespace(namespace).withName(RESOURCE_NAME).get();

        String[] allowedVersions = new String[] {};
        if (this.strimziVersions != null && this.strimziVersions.trim().length() > 0) {
            allowedVersions = this.strimziVersions.trim().split("\\s*,\\s*");
            if (allowedVersions.length == 0) {
                log.errorf("The property %s is not configured", STRIMZI_VERSIONS);
                return;
            }
        }

        if (resource == null) {
            resource = new ManagedKafkaAgentBuilder()
                    .withSpec(new ManagedKafkaAgentSpecBuilder()
                            .withClusterId(this.clusterId)
                            .withAllowedStrimziVersions(allowedVersions)
                            .build())
                    .withMetadata(new ObjectMetaBuilder().withName(RESOURCE_NAME)
                            .withNamespace(namespace)
                            .build())
                    .build();
            this.agentClient.inNamespace(namespace).createOrReplace(resource);
            log.infof("ManagedKafkaAgent CR created with name %s", RESOURCE_NAME);
        } else if (!Arrays.equals(resource.getSpec().getAllowedStrimziVersions(), allowedVersions)) {
            resource.getSpec().setAllowedStrimziVersions(allowedVersions);
            this.agentClient.inNamespace(namespace).createOrReplace(resource);
            log.infof("ManagedKafkaAgent CR updated for allowed strmzi version with name %s", RESOURCE_NAME);
        }
    }
}