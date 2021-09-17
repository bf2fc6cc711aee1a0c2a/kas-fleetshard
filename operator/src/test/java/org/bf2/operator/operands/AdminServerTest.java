package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.utils.ManagedKafkaUtils;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class AdminServerTest {

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    AdminServer adminServer;

    @Test
    void createAdminServerDeployment() throws IOException {
        ConfigMap cm = ManagedKafkaUtils.readCompanionConfigMap();

        ManagedKafka mk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withNamespace("test")
                    .withName("test-mk")
                .endMetadata()
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewEndpoint().endEndpoint()
                                .withNewVersions()
                                .withKafka("2.6.0")
                                .endVersions()
                                .build())
                .build();

        Deployment adminServerDeployment = adminServer.deploymentFrom(mk, cm);

        server.getClient().apps().deployments().create(adminServerDeployment);
        assertNotNull(server.getClient().apps().deployments()
                .inNamespace(adminServerDeployment.getMetadata().getNamespace())
                .withName(adminServerDeployment.getMetadata().getName()).get());
    }
}
