package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.Versions;
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
        ManagedKafka mk = new ManagedKafka();
        mk.getMetadata().setName("test-mk");
        mk.getMetadata().setNamespace("test");
        mk.setSpec(new ManagedKafkaSpec());
        Versions v = new Versions();
        v.setKafka("2.6.0");
        mk.getSpec().setVersions(v);

        Deployment canaryDeployment = canary.deploymentFrom(mk);

        server.getClient().apps().deployments().create(canaryDeployment);
        assertNotNull(server.getClient().apps().deployments()
                .inNamespace(canaryDeployment.getMetadata().getNamespace())
                .withName(canaryDeployment.getMetadata().getName()).get());
    }
}
