package org.bf2.performance;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceFluent.MetadataNested;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.k8s.KubeClient;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An operator manager scoped to a single namespace and specific version
 */
public class PerformanceStrimziOperatorManager extends StrimziOperatorManager {

    private static final String STRIMZI_URL_FORMAT = "https://github.com/strimzi/strimzi-kafka-operator/releases/download/%1$s/strimzi-cluster-operator-%1$s.yaml";

    public PerformanceStrimziOperatorManager(String namespace) {
        this.operatorNs = namespace;
    }

    @Override
    protected List<HasMetadata> getInstallItems(KubeClient kubeClient, URL url) throws IOException {
        // override to be version specific and handle the namespace substitution
        List<HasMetadata> installItems = super.getInstallItems(kubeClient, new URL(String.format(STRIMZI_URL_FORMAT, Environment.STRIMZI_VERSION)));
        installItems.forEach(i -> {
            if (i instanceof Namespaced) {
                i.getMetadata().setNamespace(operatorNs);
            }
        });
        return installItems;
    }

    @Override
    protected void modifyNamespace(MetadataNested<NamespaceBuilder> withName) {
        Map<String, String> nsAnnotations = new HashMap<>();
        if (Environment.KAFKA_COLLECT_LOG) {
            nsAnnotations.put(Constants.IO_KAFKA_PERFORMANCE_COLLECTPODLOG, "true");
        }
        withName.withAnnotations(nsAnnotations).withLabels(Map.of("openshift.io/cluster-monitoring", "true", "app", "kafka"));
    }

    @Override
    protected void modifyDeployment(Deployment deployment) {
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        Map<String, Quantity> limits = container.getResources().getLimits();
        limits.put("memory", Quantity.parse("1536Mi"));
        limits.put("cpu", Quantity.parse("3000m"));
        List<EnvVar> env = new ArrayList<>(container.getEnv() == null ? Collections.emptyList() : container.getEnv());
        env.add(new EnvVarBuilder().withName("STRIMZI_IMAGE_PULL_POLICY").withValue("IfNotPresent").build());
        container.setEnv(env);
    }

}
