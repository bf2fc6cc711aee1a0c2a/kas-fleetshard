package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.test.mock.QuarkusKubeMockServer;
import org.bf2.test.mock.QuarkusKubernetesMockServer;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(QuarkusKubeMockServer.class)
@QuarkusTest
public class CanaryTest {

    @QuarkusKubernetesMockServer
    static KubernetesServer server;

    @Inject
    Canary canary;

    @Test
    void createCanaryDeployment() {
        ManagedKafka mk = new ManagedKafkaBuilder()
                .withMetadata(
                        new ObjectMetaBuilder()
                                .withNamespace("test")
                                .withName("test-mk")
                                .build())
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewVersions()
                                .withKafka("2.6.0")
                                .endVersions()
                                .build())
                .build();

        Deployment canaryDeployment = canary.deploymentFrom(mk);

        server.getClient().apps().deployments().create(canaryDeployment);
        assertNotNull(server.getClient().apps().deployments()
                .inNamespace(canaryDeployment.getMetadata().getNamespace())
                .withName(canaryDeployment.getMetadata().getName()).get());
    }
}
