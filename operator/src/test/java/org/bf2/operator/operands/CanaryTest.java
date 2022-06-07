package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import org.bf2.operator.managers.OperandOverrideManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.CsvSource;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class CanaryTest {

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    Canary canary;

    @InjectMock
    OperandOverrideManager overrideManager;

    @ParameterizedTest(name = "createCanaryDeployment: {0}")
    @CsvSource({
            "shouldHaveNoDiffByDefault, test-mk-kafka-bootstrap, false, '[]', '[]', '[]'",
            "overrideContainerEnvNewAndRemove, test-mk-kafka-bootstrap, false, '[{\"name\": \"FOO\", \"value\": \"bar\"}, {\"name\": \"TOPIC_CONFIG\"}]', '[]', '[{\"op\":\"remove\",\"path\":\"/spec/template/spec/containers/0/env/11\"},{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/env/18\",\"value\":{\"name\":\"FOO\",\"value\":\"bar\"}}]'",
            "overrideContainerEnvOverrideExisting, test-mk-kafka-bootstrap, false, '[{\"name\": \"RECONCILE_INTERVAL_MS\", \"value\": \"5001\"}]', '[]', '[{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env/1/value\",\"value\":\"5001\"}]'",
            "overrideInitContainerEnvVarOverride, test-mk-kafka-bootstrap, false, '[]', '[{\"name\": \"FOO\", \"value\": \"bar\"}]', '[{\"op\":\"add\",\"path\":\"/spec/template/spec/initContainers/0/env/2\",\"value\":{\"name\":\"FOO\",\"value\":\"bar\"}}]'",
            "shouldHaveNodeAffinity, test-mk-kafka-bootstrap, true, '[]', '[]', '[{\"op\":\"add\",\"path\":\"/metadata/labels/bf2.org~1kafkaInstanceProfileType\",\"value\":\"standard\"},{\"op\":\"add\",\"path\":\"/spec/template/metadata/labels/bf2.org~1kafkaInstanceProfileType\",\"value\":\"standard\"},{\"op\":\"add\",\"path\":\"/spec/template/spec/affinity/nodeAffinity\",\"value\":{\"requiredDuringSchedulingIgnoredDuringExecution\":{\"nodeSelectorTerms\":[{\"matchExpressions\":[{\"key\":\"bf2.org/kafkaInstanceProfileType\",\"operator\":\"In\",\"values\":[\"standard\"]}]}]}}},{\"op\":\"add\",\"path\":\"/spec/template/spec/tolerations\",\"value\":[{\"effect\":\"NoExecute\",\"key\":\"bf2.org/kafkaInstanceProfileType\",\"value\":\"standard\"}]}]'",
            "shouldNotHaveInitContainersIfDevCluster, bootstrap.kas.testing.domain.tld, false, '[]', '[]', '[{\"op\":\"remove\",\"path\":\"/spec/template/spec/initContainers\"},{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env/0/value\",\"value\":\"bootstrap.kas.testing.domain.tld:443\"}]'",
    })
    void createCanaryDeployment(String name, String bootstrapServerHost, boolean useNodeAffinity, @ConvertWith(JsonArgumentConverter.class) List<EnvVar> overrideContainerEnvVars, @ConvertWith(JsonArgumentConverter.class) List<EnvVar> overrideInitContainerEnvVars, String expectedDiff) throws Exception {
        ManagedKafka mk = createManagedKafka(bootstrapServerHost);
        configureMockOverrideManager(mk, overrideContainerEnvVars, overrideInitContainerEnvVars);

        if (useNodeAffinity) {
            OperandTestUtils.useNodeAffinity(mk);
        }

        Deployment canaryDeployment = canary.deploymentFrom(mk, null);
        KafkaClusterTest.diffToExpected(canaryDeployment, "/expected/canary.yml", expectedDiff);
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

}
