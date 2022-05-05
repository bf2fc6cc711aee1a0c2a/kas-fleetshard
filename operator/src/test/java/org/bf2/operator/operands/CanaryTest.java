package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.Profile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

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
            "shouldHaveNoDiffByDefault, test-mk-kafka-bootstrap, false, '[]'",
            "shouldHaveNodeAffinity, test-mk-kafka-bootstrap, true, '[{\"op\":\"add\",\"path\":\"/metadata/labels/bf2.org~1kafkaInstanceProfileType\",\"value\":\"standard\"},{\"op\":\"add\",\"path\":\"/spec/template/metadata/labels/bf2.org~1kafkaInstanceProfileType\",\"value\":\"standard\"},{\"op\":\"add\",\"path\":\"/spec/template/spec/affinity/nodeAffinity\",\"value\":{\"requiredDuringSchedulingIgnoredDuringExecution\":{\"nodeSelectorTerms\":[{\"matchExpressions\":[{\"key\":\"bf2.org/kafkaInstanceProfileType\",\"operator\":\"In\",\"values\":[\"standard\"]}]}]}}}]'",
            "shouldNotHaveInitContainersIfDevCluster, bootstrap.kas.testing.domain.tld, false, '[{\"op\":\"remove\",\"path\":\"/spec/template/spec/initContainers\"},{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env/0/value\",\"value\":\"bootstrap.kas.testing.domain.tld:443\"}]'",
    })
    void createCanaryDeployment(String name, String bootstrapServerHost, boolean useNodeAffinity, String expectedDiff) throws Exception {
        ManagedKafka mk = createManagedKafka(bootstrapServerHost);

        if (useNodeAffinity) {
            useNodeAffinity(mk);
        }

        Deployment canaryDeployment = canary.deploymentFrom(mk, null);
        KafkaClusterTest.diffToExpected(canaryDeployment, "/expected/canary.yml", expectedDiff);
    }

    static void useNodeAffinity(ManagedKafka mk) {
        mk.setMetadata(
                new ObjectMetaBuilder(mk.getMetadata()).addToLabels(ManagedKafka.PROFILE_TYPE, "standard").build());

        ManagedKafkaAgent agent = ManagedKafkaAgentResourceClient.getDummyInstance();
        agent.getSpec().setDeveloper(new Profile());
        agent.getSpec().setStandard(new Profile());
        InformerManager manager = Mockito.mock(InformerManager.class);
        Mockito.when(manager.getLocalAgent()).thenReturn(agent);
        QuarkusMock.installMockForType(manager, InformerManager.class);
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

}
