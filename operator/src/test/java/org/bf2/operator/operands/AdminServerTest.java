package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.strimzi.api.kafka.model.KafkaBuilder;
import org.bf2.operator.managers.OperandOverrideManager;
import org.bf2.operator.managers.OperandOverrideManager.OperandOverride;
import org.bf2.operator.managers.SecuritySecretManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.TlsKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class AdminServerTest {

    @Inject
    KubernetesClient client;

    @Inject
    AdminServer adminServer;

    @BeforeEach
    public void setup() {
        client.apps()
                .deployments()
                .inNamespace(client.getNamespace())
                .withLabel("app.kubernetes.io/component", "adminserver")
                .delete();
        client.secrets().inNamespace(client.getNamespace()).delete();
    }

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
        "test-mk-q,     1, true,  false, false, '[]', /expected/adminserver.yml",
        "test-mk-tls-q, 2, true,  true, false, '[]', /expected/adminserver-tls.yml",
        "test-mk-q,     2, true,  false, true, '[]', /expected/adminserver-affinity.yml",
        "test-mk-q,     1, true,  false, false, '[{\"name\": \"FOO\", \"value\": \"bar\"}]', /expected/adminserver-envoverride.yml",
    })
    void createAdminServerDeployment(String name, String versionString, boolean quarkusBased, boolean tls, boolean useNodeAffinity, @ConvertWith(JsonArgumentConverter.class) List<EnvVar> overrideContainerEnvVars, String expectedResource) throws Exception {
        ManagedKafka mk = buildBasicManagedKafka(name, versionString, tls ? new TlsKeyPair() : null);

        if (useNodeAffinity) {
            OperandTestUtils.useNodeAffinity(mk);
        }

        OperandOverride override = new OperandOverride();
        override.setImage("quay.io/mk-ci-cd/kafka-admin-api:0.8.0");
        override.setEnv(overrideContainerEnvVars);

        OperandOverrideManager overrideManager = Mockito.mock(OperandOverrideManager.class);
        Mockito.when(overrideManager.getAdminServerOverride(versionString))
            .thenReturn(override);
        Mockito.when(overrideManager.getAdminServerImage(versionString))
            .thenReturn(override.image);
        QuarkusMock.installMockForType(overrideManager, OperandOverrideManager.class);

        Deployment adminServerDeployment = adminServer.deploymentFrom(mk, null);

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

    @Test
    void testDeploymentCreatedWhenSecretsExist() {
        KafkaCluster kafkaCluster = Mockito.mock(KafkaCluster.class);
        QuarkusMock.installMockForType(kafkaCluster, KafkaCluster.class);
        Mockito.when(kafkaCluster.cachedKafka(Mockito.any()))
                .thenReturn(new KafkaBuilder().withNewStatus().withClusterId("all-good").endStatus().build());

        ManagedKafka mk = buildBasicManagedKafka("test", "0.26.0-10", new TlsKeyPair());

        Resource<Deployment> deployment = client.apps().deployments()
                .inNamespace(client.getNamespace())
                .withName(AdminServer.adminServerName(mk));

        // No secrets present initially
        assertNull(deployment.get());
        adminServer.createOrUpdate(mk);
        assertNull(deployment.get());

        client.secrets()
            .inNamespace(client.getNamespace())
            .create(new SecretBuilder()
                    .withNewMetadata()
                        .withName(SecuritySecretManager.strimziClusterCaCertSecret(mk))
                    .endMetadata()
                    .withData(Map.of("ca.crt", "dummycacert"))
                    .build());

        // 1 of 2 required secrets exist
        adminServer.createOrUpdate(mk);
        assertNull(deployment.get());

        client.secrets()
            .inNamespace(client.getNamespace())
            .create(new SecretBuilder()
                    .withNewMetadata()
                        .withName(SecuritySecretManager.kafkaTlsSecretName(mk))
                    .endMetadata()
                    .withData(Map.of("tls.crt", "dummycert")) // Missing `tls.key`
                    .build());

        // 2 of 2 required secrets exist, but `tls.key` is missing from TLS secret
        adminServer.createOrUpdate(mk);
        assertNull(deployment.get());

        client.secrets()
            .inNamespace(client.getNamespace())
            .withName(SecuritySecretManager.kafkaTlsSecretName(mk))
            .edit(tlsSecret -> {
                tlsSecret.getData().put("tls.key", "dummykey");
                return tlsSecret;
            });

        // 2 of 2 required secrets fully exist
        adminServer.createOrUpdate(mk);
        assertNotNull(deployment.get());

        // check kafka delay
        deployment.delete();
        Mockito.when(kafkaCluster.cachedKafka(Mockito.any())).thenReturn(new KafkaBuilder().build());
        adminServer.createOrUpdate(mk);
        assertNull(deployment.get());
    }

    @Test
    void testReservedDeployment() throws Exception {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk = new ManagedKafkaBuilder(mk).editMetadata()
                .withNamespace("reserved")
                .addToLabels(ManagedKafka.DEPLOYMENT_TYPE, ManagedKafka.RESERVED_DEPLOYMENT_TYPE)
                .endMetadata()
                .build();
        adminServer.createOrUpdate(mk);
        Resource<Deployment> deployment = client.apps().deployments()
                .inNamespace(AdminServer.adminServerNamespace(mk))
                .withName(AdminServer.adminServerName(mk));

        Deployment reserved = deployment.get();
        assertEquals("pause", reserved.getSpec().getTemplate().getSpec().getContainers().get(0).getName());
    }

}
