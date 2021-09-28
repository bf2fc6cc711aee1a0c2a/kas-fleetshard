package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class CanaryTest {

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    Canary canary;

    @Test
    void createCanaryDeployment() throws Exception {
        ManagedKafka mk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withNamespace("test")
                    .withName("test-mk")
                .endMetadata()
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewVersions()
                                .withKafka("2.6.0")
                                .endVersions()
                                .build())
                .build();

        Deployment canaryDeployment = canary.deploymentFrom(mk, null);
        KafkaClusterTest.diffToExpected(canaryDeployment, "/expected/canary.yml");
    }
}
