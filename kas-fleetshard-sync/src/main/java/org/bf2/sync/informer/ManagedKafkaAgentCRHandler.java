package org.bf2.sync.informer;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.runtime.StartupEvent;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jose4j.base64url.Base64;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import java.util.Arrays;

/**
 * TODO: This is throw away code until the Control Plane API for ManagedkafkaAgent CR is defined.
 */
@ApplicationScoped
public class ManagedKafkaAgentCRHandler implements ResourceEventHandler<Secret> {
    public static final String SECRET_NAME = "addon-kas-fleetshard-operator-parameters";
    private static final String RESOURCE_NAME = "managed-agent";

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "cluster.id", defaultValue = "testing")
    private String clusterId;

    private MixedOperation<ManagedKafkaAgent, ManagedKafkaAgentList, Resource<ManagedKafkaAgent>> agentClient;

    @PostConstruct
    void onStart() {
        this.agentClient = kubernetesClient.customResources(ManagedKafkaAgent.class, ManagedKafkaAgentList.class);
    }

    void onStartUp(@Observes StartupEvent ev) {
        Secret secret = this.kubernetesClient.secrets()
                .inNamespace(this.kubernetesClient.getNamespace())
                .withName(SECRET_NAME).get();

        if (secret != null) {
            createOrUpdateManagedKafkaAgentCR(secret);
        }
    }

    @Override
    public void onAdd(Secret obj) {
        if (isAddOnFleetShardSecret(obj)) {
            createOrUpdateManagedKafkaAgentCR(obj);
        }
    }

    @Override
    public void onUpdate(Secret oldObj, Secret newObj) {
        if(isAddOnFleetShardSecret(oldObj)
                && !oldObj.getMetadata().getResourceVersion().equals(newObj.getMetadata().getResourceVersion())) {
            createOrUpdateManagedKafkaAgentCR(newObj);
        }
    }

    @Override
    public void onDelete(Secret obj, boolean deletedFinalStateUnknown) {
        // on delete, noop until the this is corrected, do not add/delete any meantime
    }

    boolean isAddOnFleetShardSecret(Secret obj) {
        return obj.getMetadata().getName().equals(SECRET_NAME);
    }

    private void createOrUpdateManagedKafkaAgentCR(Secret secret) {
        String namespace = this.kubernetesClient.getNamespace();
        String[] allowedVersions = new String(Base64.decode(secret.getData().get("strimzi.allowed_versions")))
                .trim().split("\\s*,\\s*");

        ManagedKafkaAgent resource = this.agentClient
                .inNamespace(this.kubernetesClient.getNamespace())
                .withName(RESOURCE_NAME).get();

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
        } else if (!Arrays.equals(resource.getSpec().getAllowedStrimziVersions(), allowedVersions)) {
            resource.getSpec().setAllowedStrimziVersions(allowedVersions);
            this.agentClient.inNamespace(namespace).createOrReplace(resource);
        }
    }
}