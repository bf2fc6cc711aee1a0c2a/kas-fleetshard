package org.bf2.performance;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceFluent.MetadataNested;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.k8s.KubeClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Overrides the simple operator manager to add monitoring, version control, and operator resource limits
 */
public class PerformanceStrimziOperatorManager extends StrimziOperatorManager {

    @Override
    public CompletableFuture<Void> installStrimzi(KubeClient kubeClient) throws Exception {
        // TODO: if there seems to be another install that would be bad...
        return super.doInstall(kubeClient); // always do the install
    }

    @Override
    protected void createClusterRoleBinding(KubeClient kubeClient, ClusterRoleBinding crb) {
        kubeClient.client().rbac().clusterRoleBindings().createOrReplace(crb);
    }

    @Override
    protected Namespace namespaceToCreate(MetadataNested<NamespaceBuilder> withName) {
        Map<String, String> nsAnnotations = new HashMap<>();
        if (Environment.KAFKA_COLLECT_LOG) {
            nsAnnotations.put(Constants.ORG_BF2_KAFKA_PERFORMANCE_COLLECTPODLOG, "true");
        }
        withName.withAnnotations(nsAnnotations);
        return withName.endMetadata().build();
    }

    @Override
    protected void modifyDeployment(Deployment deployment) {
        super.modifyDeployment(deployment);
        // TODO: this is probably needed only when testing bin-packing
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        Map<String, Quantity> limits = container.getResources().getLimits();
        limits.put("memory", Quantity.parse("1536Mi"));
        limits.put("cpu", Quantity.parse("3000m"));
        List<EnvVar> env = new ArrayList<>(container.getEnv() == null ? Collections.emptyList() : container.getEnv());
        env.add(new EnvVarBuilder().withName("STRIMZI_IMAGE_PULL_POLICY").withValue("IfNotPresent").build());
        container.setEnv(env);
    }

}
