package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Service;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.inject.Inject;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class CanaryTest {

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    Canary canary;

    @ParameterizedTest(name = "createCanaryDeployment: {0}")
    @CsvSource({
            "shouldHaveNoDiffByDefault, test-mk-kafka-bootstrap, '[]'",
            "shouldNotHaveInitContainersIfDevCluster, bootstrap.kas.testing.domain.tld, '[{\"op\":\"remove\",\"path\":\"/spec/template/spec/initContainers\"},{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env/0/value\",\"value\":\"bootstrap.kas.testing.domain.tld:443\"}]'",
    })
    void createCanaryDeployment(String name, String bootstrapServerHost, String expectedDiff) throws Exception {
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
                                .withNewEndpoint()
                                .withBootstrapServerHost(bootstrapServerHost)
                                .endEndpoint()
                                .build())
                .build();

        Deployment canaryDeployment = canary.deploymentFrom(mk, null);
        KafkaClusterTest.diffToExpected(canaryDeployment, "/expected/canary.yml", expectedDiff);
    }

    @Test
    void createCanaryService() throws Exception {
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
                                .withNewEndpoint()
                                .withBootstrapServerHost("test-mk-kafka-bootstrap")
                                .endEndpoint()
                                .build())
                .build();

        Service canaryService = canary.serviceFrom(mk, null);

        server.getClient().services().create(canaryService);
        assertNotNull(server.getClient().services()
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
}
