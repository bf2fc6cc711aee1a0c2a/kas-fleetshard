package org.bf2.systemtest.operator;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import org.apache.commons.collections.map.SingletonMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.test.Environment;
import org.bf2.test.TestUtils;
import org.bf2.test.k8s.KubeClient;

import java.io.FileInputStream;
import java.util.Map;

public class FleetShardOperatorManager {
    private static final Logger LOGGER = LogManager.getLogger(FleetShardOperatorManager.class);
    private static final String OPERATOR_NS = "kas-fleetshard";

    public static void deployFleetShardOperator(KubeClient kubeClient) throws Exception {
        LOGGER.info("Installing kas-fleetshard-operator");
        LOGGER.info("Installing CRD");
        kubeClient.client().load(new FileInputStream(Environment.CRD_PATH.toString())).get().forEach(crd ->
                kubeClient.client().apiextensions().v1beta1().customResourceDefinitions().createOrReplace((CustomResourceDefinition) crd));

        kubeClient.client().namespaces().createOrReplace(new NamespaceBuilder().withNewMetadata().withName(OPERATOR_NS).endMetadata().build());
        LOGGER.info("Installing operator from files: {}", Environment.YAML_BUNDLE_PATH.toString());

        KubeClient.getInstance().apply(OPERATOR_NS, TestUtils.replacer(Map.of("##IMAGE##", Environment.FLEET_SHARD_IMAGE)), Environment.YAML_BUNDLE_PATH);

        TestUtils.waitFor("Operator ready", 1_000, 120_000, FleetShardOperatorManager::isOperatorInstalled);
        LOGGER.info("Agent is deployed");
    }

    public static boolean isOperatorInstalled() {
        return KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                .list().getItems().stream().anyMatch(pod -> pod.getMetadata().getName().contains("kas-fleetshard-operator")) &&
                KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                .list().getItems().stream().filter(pod ->
                        pod.getMetadata().getName().contains("kas-fleetshard-operator")).findFirst().get().getStatus().getPhase().equals("Running");
    }

    public static void deleteFleetShardOperator(KubeClient kubeClient) throws InterruptedException {
        LOGGER.info("Deleting managedkafkas and kas-fleetshard-operator");
        var mkCli = kubeClient.client().customResources(ManagedKafka.class);
        mkCli.inAnyNamespace().list().getItems().forEach(mk -> mkCli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).delete());
        Thread.sleep(10_000);
        kubeClient.client().namespaces().withName(OPERATOR_NS).delete();
        LOGGER.info("kas-fleetshard-operator is deleted");
    }
}
