package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import org.bf2.operator.managers.OperandOverrideManager;
import org.bf2.operator.managers.OperandOverrideManager.OperandOverride;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.TlsKeyPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

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

    static ManagedKafka buildBasicManagedKafka(String name, String strimziVersion, TlsKeyPair tls) {
        return new ManagedKafkaBuilder()
                .withNewMetadata()
                .withNamespace("test")
                .withName(name)
            .endMetadata()
            .withSpec(
                    new ManagedKafkaSpecBuilder()
                            .withNewCapacity()
                                .withMaxPartitions(1000)
                                .endCapacity()
                            .withNewEndpoint()
                                .withTls(tls)
                                .endEndpoint()
                            .withNewVersions()
                                .withKafka("2.6.0")
                                .withStrimzi(strimziVersion)
                                .endVersions()
                            .build())
            .build();
    }

    @ParameterizedTest
    @CsvSource({
        "test-mk-q,     1, true,  false, /expected/adminserver-quarkus.yml",
        "test-mk-tls-q, 2, true,  true,  /expected/adminserver-tls-quarkus.yml",
        "test-mk,       3, false, false, /expected/adminserver.yml",
        "test-mk-tls,   4, false, true,  /expected/adminserver-tls.yml"
    })
    void createAdminServerDeployment(String name, String versionString, boolean quarkusBased, boolean tls, String expectedResource) throws Exception {
        ManagedKafka mk = buildBasicManagedKafka(name, versionString, tls ? new TlsKeyPair() : null);

        OperandOverride override = new OperandOverride();
        override.setImage("quay.io/mk-ci-cd/kafka-admin-api:0.7.0");
        override.setAdditionalProperty("quarkus-based", quarkusBased);

        OperandOverrideManager overrideManager = Mockito.mock(OperandOverrideManager.class);
        Mockito.when(overrideManager.getAdminServerOverride(versionString))
            .thenReturn(override);
        Mockito.when(overrideManager.getAdminServerImage(versionString))
            .thenReturn(override.image);
        QuarkusMock.installMockForType(overrideManager, OperandOverrideManager.class);

        Deployment adminServerDeployment = adminServer.deploymentFrom(mk, null);
        server.getClient().apps().deployments().create(adminServerDeployment);

        assertNotNull(server.getClient().apps().deployments()
                .inNamespace(adminServerDeployment.getMetadata().getNamespace())
                .withName(adminServerDeployment.getMetadata().getName()).get());

        KafkaClusterTest.diffToExpected(adminServerDeployment, expectedResource);
    }

    @Test
    void routeUriPlain() throws Exception {
        Route route = new RouteBuilder().withNewSpec()
                .withHost("test.net")
            .endSpec()
            .build();

        assertEquals("http://test.net", adminServer.getRouteURI(route));
    }

    @Test
    void routeUriTls() throws Exception {
        Route route = new RouteBuilder().withNewSpec()
                .withHost("test.net")
                .withNewTls()
                .endTls()
            .endSpec()
            .build();

        assertEquals("https://test.net", adminServer.getRouteURI(route));
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

    @Test
    void testLabels() throws Exception {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setLabels(Map.of(ManagedKafka.PROFILE_TYPE, "something"));

        assertEquals("something", adminServer.buildLabels("my-admin", mk).get(ManagedKafka.PROFILE_TYPE));
    }

}
