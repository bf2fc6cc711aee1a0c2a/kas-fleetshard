package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class AdminServerTest {

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    AdminServer adminServer;

    @Test
    void createAdminServerDeployment() {
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

        Deployment adminServerDeployment = adminServer.deploymentFrom(mk, null);

        server.getClient().apps().deployments().create(adminServerDeployment);
        assertNotNull(server.getClient().apps().deployments()
                .inNamespace(adminServerDeployment.getMetadata().getNamespace())
                .withName(adminServerDeployment.getMetadata().getName()).get());
    }
}
