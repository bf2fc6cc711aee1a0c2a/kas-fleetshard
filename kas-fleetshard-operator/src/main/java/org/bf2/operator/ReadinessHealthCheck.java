package org.bf2.operator;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.Kafka;

@Readiness
@ApplicationScoped
public class ReadinessHealthCheck implements HealthCheck {

    @Inject
    protected KubernetesClient kubernetesClient;

    @Override
    public HealthCheckResponse call() {
        if (isStrimziInstalled(this.kubernetesClient.getNamespace())) {
            return HealthCheckResponse.up("Ready, Strimzi Operator found");
        }
        return HealthCheckResponse.down("Not Ready, Strimzi Operator not found");
    }

    boolean isStrimziInstalled(String namespace) {
        final String strimziCrdName = CustomResource.getCRDName(Kafka.class);
        List<CustomResourceDefinition> crds = this.kubernetesClient.apiextensions().v1().customResourceDefinitions()
                .withLabel("app", "strimzi").list().getItems();

        boolean crdFound = false;
        for (CustomResourceDefinition crd : crds) {
            if (crd.getMetadata().getName().equals(strimziCrdName)) {
                crdFound = true;
                break;
            }
        }

        if (crdFound) {
            // strimi deployment starts with "strimzi-cluster-operator-v*"
            List<Deployment> deployments = this.kubernetesClient.apps().deployments().inNamespace(namespace)
                    .list().getItems();
            for (Deployment deployment : deployments) {
                if (deployment.getMetadata().getName().startsWith("strimzi-cluster-operator-v")) {
                    for (DeploymentCondition condition : deployment.getStatus().getConditions()) {
                        if (condition.getType().equals("Available") && condition.getStatus().equalsIgnoreCase("True")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}