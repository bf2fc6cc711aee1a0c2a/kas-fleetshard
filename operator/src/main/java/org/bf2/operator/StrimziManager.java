package org.bf2.operator;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class StrimziManager {

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    /**
     * Execute the discovery of the Strimzi operators installed on the Kubernetes cluster
     * via a specific label applied on them
     *
     * @return list with Deployment names of Strimzi operator
     */
    public List<String> getStrimziVersions() {

        // checking the ReplicaSet instead of Deployments is a temporary workaround waiting for OpenShift 4.8 (with OLM operator 1.18.0)
        // OLM operator 1.17.0 (on OpenShift 4.7) doesn't support feature to set labels on Deployments from inside the CSV
        // OLM operator 1.18.0 (on OpenShift 4.8) support the above feature
        ReplicaSetList list = this.kubernetesClient.apps()
                .replicaSets()
                .inAnyNamespace()
                .withLabelIn("app.kubernetes.io/part-of", "managed-kafka")
                .list();

        List<String> strimziVersions = new ArrayList<>();
        log.debug("Strimzi installations");
        if (!list.getItems().isEmpty()) {
            for (ReplicaSet replicaSet : list.getItems()) {

                String deploymentName = replicaSet.getMetadata().getOwnerReferences().get(0).getName();
                Optional<Deployment> optDeployment = this.kubernetesClient.apps()
                        .deployments()
                        .inNamespace(replicaSet.getMetadata().getNamespace())
                        .list().getItems()
                        .stream().filter(d -> d.getMetadata().getName().equals(deploymentName)).findFirst();

                if (optDeployment.isPresent()) {
                    Deployment deployment = optDeployment.get();
                    // check it's ready
                    boolean isReady = deployment.getStatus() != null && deployment.getStatus().getReadyReplicas() != null && deployment.getStatus().getReadyReplicas().equals(deployment.getSpec().getReplicas());
                    if (isReady) {
                        strimziVersions.add(deployment.getMetadata().getName());
                    }
                    log.debugf("\t - %s [%s]", deployment.getMetadata().getName(), isReady);
                }
            }
        }
        return strimziVersions;
    }
}
