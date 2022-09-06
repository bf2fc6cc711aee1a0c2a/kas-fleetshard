package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.SecretVolumeSource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.strimzi.api.kafka.model.KafkaBuilder;
import org.bf2.operator.managers.OperandOverrideManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.TlsKeyPairBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class CanaryTest {

    @Inject
    KubernetesClient client;

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    Canary canary;

    @InjectMock
    OperandOverrideManager overrideManager;

    @ParameterizedTest(name = "createCanaryDeployment: {0}")
    @CsvSource({
            "shouldHaveNoDiffByDefault, test-mk-kafka-bootstrap, false, '[]', '[]', /expected/canary.yml",
            "overrideContainerEnvNewAndRemove, test-mk-kafka-bootstrap, false, '[{\"name\": \"FOO\", \"value\": \"main\"}, {\"name\": \"TOPIC_CONFIG\"}]','[{\"name\": \"FOO\", \"value\": \"init\"}]', /expected/canary-envoverride.yml",
            "shouldHaveNodeAffinity, test-mk-kafka-bootstrap, true, '[]', '[]', /expected/canary-nodeaffinity.yml",
            "shouldNotHaveInitContainersIfDevCluster, bootstrap.kas.testing.domain.tld, false, '[]', '[]', /expected/canary-devcluster.yml"
    })
    void createCanaryDeployment(String name, String bootstrapServerHost, boolean useNodeAffinity, @ConvertWith(JsonArgumentConverter.class) List<EnvVar> overrideContainerEnvVars, @ConvertWith(JsonArgumentConverter.class) List<EnvVar> overrideInitContainerEnvVars, String expectedResource) throws Exception {
        ManagedKafka mk = createManagedKafka(bootstrapServerHost);
        configureMockOverrideManager(mk, overrideContainerEnvVars, overrideInitContainerEnvVars);

        if (useNodeAffinity) {
            OperandTestUtils.useNodeAffinity(mk);
        }

        Deployment canaryDeployment = canary.deploymentFrom(mk, null);
        KafkaClusterTest.diffToExpected(canaryDeployment, expectedResource);
    }

    private ManagedKafka createManagedKafka(String bootstrapServerHost) {
        ManagedKafka mk = new ManagedKafkaBuilder()
                .withNewMetadata()
                .withNamespace("test")
                .withName("test-mk")
                .endMetadata()
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewVersions()
                                .withKafka("2.6.0")
                                .withStrimzi("0.26")
                                .endVersions()
                                .withNewEndpoint()
                                .withBootstrapServerHost(bootstrapServerHost)
                                .endEndpoint()
                                .build())
                .build();
        return mk;
    }

    @Test
    void createCanaryService() throws Exception {
        ManagedKafka mk = createManagedKafka("test-mk-kafka-bootstrap");

        Service canaryService = canary.serviceFrom(mk, null);

        server.getClient().services().create(canaryService);
        assertNotNull(server.getClient()
                .services()
                .inNamespace(canaryService.getMetadata().getNamespace())
                .withName(canaryService.getMetadata().getName())
                .get());
        KafkaClusterTest.diffToExpected(canaryService, "/expected/canary-service.yml");
    }

    @Test
    void testLabels() throws Exception {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setLabels(Map.of(ManagedKafka.PROFILE_TYPE, "something"));

        assertEquals("something", canary.buildLabels("my-canary", mk).get(ManagedKafka.PROFILE_TYPE));
    }

    private void configureMockOverrideManager(ManagedKafka mk, List<EnvVar> override, List<EnvVar> overrideInitContainerEnvVars) {
        String strimzi = mk.getSpec().getVersions().getStrimzi();
        OperandOverrideManager.Canary canary = new OperandOverrideManager.Canary();
        canary.setEnv(override);
        canary.init.setEnv(overrideInitContainerEnvVars);
        when(overrideManager.getCanaryOverride(strimzi)).thenReturn(canary);
        when(overrideManager.getCanaryImage(strimzi)).thenReturn("quay.io/mk-ci-cd/strimzi-canary:0.2.0-220111183833");
        when(overrideManager.getCanaryInitImage(strimzi)).thenReturn("quay.io/mk-ci-cd/strimzi-canary:0.2.0-220111183833");
    }

    @ParameterizedTest
    @CsvSource({
            "some-cert, some-key, /tmp/tls-ca-cert/tls.crt, test-mk-tls-secret",
            "         ,         , /tmp/tls-ca-cert/ca.crt , test-mk-cluster-ca-cert"
    })
    void canaryTlsConfigurationTest(String tlsCert, String tlsKey, String expectedCertPath, String expectedSecretName) throws Exception {
        ManagedKafka mk = createManagedKafka("bootstrap:443");
        if (tlsCert != null && tlsKey != null) {
            mk.getSpec().getEndpoint().setTls(new TlsKeyPairBuilder().withCert(tlsCert).withKey(tlsKey).build());
        }
        configureMockOverrideManager(mk, Collections.emptyList(), Collections.emptyList());
        Deployment canaryDeployment = canary.deploymentFrom(mk, null);

        Optional<String> actualCertPath = canaryDeployment.getSpec()
            .getTemplate()
            .getSpec()
            .getContainers()
            .stream()
            .flatMap(container -> container.getEnv().stream())
            .filter(env -> "TLS_CA_CERT".equals(env.getName()))
            .map(EnvVar::getValue)
            .findFirst();

        assertTrue(actualCertPath.isPresent());
        assertEquals(expectedCertPath, actualCertPath.get());

        Optional<String> actualSecretName = canaryDeployment.getSpec()
            .getTemplate()
            .getSpec()
            .getVolumes()
            .stream()
            .filter(vol -> Canary.canaryTlsVolumeName(mk).equals(vol.getName()))
            .map(Volume::getSecret)
            .map(SecretVolumeSource::getSecretName)
            .findFirst();

        assertTrue(actualSecretName.isPresent());
        assertEquals(expectedSecretName, actualSecretName.get());
    }

    @Test
    void testDelayedDeployment() throws Exception {
        KafkaCluster kafkaCluster = Mockito.mock(KafkaCluster.class);
        QuarkusMock.installMockForType(kafkaCluster, KafkaCluster.class);

        Mockito.when(kafkaCluster.cachedKafka(Mockito.any())).thenReturn(null);

        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        canary.createOrUpdate(mk);
        Resource<Deployment> deployment = client.apps().deployments()
                .inNamespace(Canary.canaryNamespace(mk))
                .withName(Canary.canaryName(mk));
        assertNull(deployment.get());

        // should proceed once ready
        Mockito.when(kafkaCluster.cachedKafka(Mockito.any()))
            .thenReturn(new KafkaBuilder().withNewStatus().withClusterId("all-good").endStatus().build());
        configureMockOverrideManager(mk, Collections.emptyList(), Collections.emptyList());
        canary.createOrUpdate(mk);
        assertNotNull(deployment.get());
    }
}
