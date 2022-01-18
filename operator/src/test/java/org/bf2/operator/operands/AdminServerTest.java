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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class AdminServerTest {

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    AdminServer adminServer;

    static ManagedKafka buildBasicManagedKafka() {
        return new ManagedKafkaBuilder()
                .withNewMetadata()
                .withNamespace("test")
                .withName("test-mk")
            .endMetadata()
            .withSpec(
                    new ManagedKafkaSpecBuilder()
                            .withNewCapacity()
                                .withMaxPartitions(1000)
                                .endCapacity()
                            .withNewEndpoint()
                                .endEndpoint()
                            .withNewVersions()
                                .withKafka("2.6.0")
                                .endVersions()
                            .build())
            .build();
    }

    @Test
    void createAdminServerDeployment() throws Exception {
        ManagedKafka mk = buildBasicManagedKafka();
        Deployment adminServerDeployment = adminServer.deploymentFrom(mk, null);

        server.getClient().apps().deployments().create(adminServerDeployment);
        assertNotNull(server.getClient().apps().deployments()
                .inNamespace(adminServerDeployment.getMetadata().getNamespace())
                .withName(adminServerDeployment.getMetadata().getName()).get());
        KafkaClusterTest.diffToExpected(adminServerDeployment, "/expected/adminserver.yml");

    }

    @Test
    void testBuildRouteAnnotationsWithRateLimitDisabled() throws Exception {
        KafkaInstanceConfiguration config = new KafkaInstanceConfiguration();
        config.getAdminserver().setRateLimitEnabled(false);
        Map<String, String> annotations = adminServer.buildRouteAnnotations(config);
        assertNotNull(annotations);
        assertTrue(annotations.isEmpty());
    }

    @Test
    void testBuildRouteAnnotations() throws Exception {
        KafkaInstanceConfiguration config = new KafkaInstanceConfiguration();
        config.getAdminserver().setRateLimitEnabled(true);
        Map<String, String> annotations = adminServer.buildRouteAnnotations(config);
        assertNotNull(annotations);
        assertEquals(3, annotations.size());
        assertTrue(annotations.keySet().containsAll(List.of(
                AdminServer.RATE_LIMIT_ANNOTATION,
                AdminServer.RATE_LIMIT_ANNOTATION_CONCURRENT_TCP,
                AdminServer.RATE_LIMIT_ANNOTATION_TCP_RATE)));
    }

}
