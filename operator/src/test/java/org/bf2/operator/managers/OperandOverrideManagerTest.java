package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.operator.MockProfile;
import org.bf2.operator.managers.OperandOverrideManager.Kafka;
import org.bf2.operator.managers.OperandOverrideManager.Kafka.ListenerOverride;
import org.bf2.operator.managers.OperandOverrideManager.OperandOverride;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTestResource(KubernetesServerTestResource.class)
@TestProfile(MockProfile.class)
@QuarkusTest
public class OperandOverrideManagerTest {

    private static final String STRIMZI_CLUSTER_OPERATOR_VER = "strimzi-cluster-operator-0.26-1";
    private static final ObjectMeta OVERRIDE_METADATA = new ObjectMetaBuilder().withName(STRIMZI_CLUSTER_OPERATOR_VER).build();

    @Inject
    KubernetesClient client;

    @Inject
    OperandOverrideManager overrideManager;

    @AfterEach
    public void cleanup() {
        overrideManager.resetOverrides();
    }

    @Test
    void imageOverride() {
        String defaultVersion = overrideManager.getCanaryImage(STRIMZI_CLUSTER_OPERATOR_VER);

        overrideManager.updateOverrides(new ConfigMapBuilder()
                .withMetadata(OVERRIDE_METADATA)
                .withData(Collections.singletonMap(OperandOverrideManager.OPERANDS_YAML,
                          "canary: \n"
                        + "  image: something\n"
                        + "  notused: value\n"
                        + "  init: \n"
                        + "    image: somethingelse\n"))
                .build());

        String override = overrideManager.getCanaryImage(STRIMZI_CLUSTER_OPERATOR_VER);

        assertEquals("something", override);
        assertNotEquals(defaultVersion, override);

        String initOverride = overrideManager.getCanaryInitImage(STRIMZI_CLUSTER_OPERATOR_VER);

        assertEquals("somethingelse", initOverride);
    }

    @Test
    void environmentOverride() {
        overrideManager.updateOverrides(new ConfigMapBuilder()
                .withMetadata(OVERRIDE_METADATA)
                .withData(Collections.singletonMap(OperandOverrideManager.OPERANDS_YAML,
                          "canary: \n"
                        + "  image: something\n"
                        + "  env:\n"
                        + "  - name: FOO\n"
                        + "    value: bar\n"))
                .build());

        OperandOverride canaryOverride = overrideManager.getCanaryOverride(STRIMZI_CLUSTER_OPERATOR_VER);
        assertEquals(1, canaryOverride.env.size());
        assertEquals("FOO", canaryOverride.env.get(0).getName());
    }
    @Test
    void applyEnvironmentOverridesProvidesAdditionalEnvVar() {
        overrideManager.updateOverrides(new ConfigMapBuilder()
                .withMetadata(OVERRIDE_METADATA)
                .withData(Collections.singletonMap(OperandOverrideManager.OPERANDS_YAML,
                        "canary: \n"
                                + "  env:\n"
                                + "  - name: FOO\n"
                                + "    value: bar\n"))
                .build());

        OperandOverride canaryOverride = overrideManager.getCanaryOverride(STRIMZI_CLUSTER_OPERATOR_VER);

        EnvVar containerEnv1 = new EnvVar("JAZ", "paz", null);
        List<EnvVar> envVars = canaryOverride.applyEnvironmentTo(List.of(containerEnv1));

        assertEquals(2, envVars.size());
        assertEquals(containerEnv1, envVars.get(0));
        assertEquals(new EnvVar("FOO", "bar", null), envVars.get(1));
    }

    @Test
    void applyEnvironmentOverridesReplacesExistingEnvVar() {
        overrideManager.updateOverrides(new ConfigMapBuilder()
                .withMetadata(OVERRIDE_METADATA)
                .withData(Collections.singletonMap(OperandOverrideManager.OPERANDS_YAML,
                          "canary: \n"
                        + "  env:\n"
                        + "  - name: FOO\n"
                        + "    value: bar\n"))
                .build());

        OperandOverride canaryOverride = overrideManager.getCanaryOverride(STRIMZI_CLUSTER_OPERATOR_VER);

        List<EnvVar> envVars = canaryOverride.applyEnvironmentTo(List.of(new EnvVar("FOO", "baz", null)));

        assertEquals(1, envVars.size());
        assertEquals("FOO", envVars.get(0).getName());
        assertEquals("bar", envVars.get(0).getValue());
    }

    @Test
    void applyEnvironmentOverridesDeletesEnvVar() {
        overrideManager.updateOverrides(new ConfigMapBuilder()
                .withMetadata(OVERRIDE_METADATA)
                .withData(Collections.singletonMap(OperandOverrideManager.OPERANDS_YAML,
                          "canary: \n"
                        + "  env:\n"
                        + "  - name: JAZ\n"))
                .build());

        OperandOverride canaryOverride = overrideManager.getCanaryOverride(STRIMZI_CLUSTER_OPERATOR_VER);

        EnvVar containerEnv1 = new EnvVar("JAZ", "goodbye!", null);
        EnvVar containerEnv2 = new EnvVar("FOO", "bar", null);
        List<EnvVar> envVars = canaryOverride.applyEnvironmentTo(List.of(containerEnv1, containerEnv2));

        assertEquals(1, envVars.size());
        assertEquals(containerEnv2, envVars.get(0));
    }

    @Test
    void kafkaListenerOverride() {
        overrideManager.updateOverrides(new ConfigMapBuilder()
                .withMetadata(OVERRIDE_METADATA)
                .withData(Collections.singletonMap(OperandOverrideManager.OPERANDS_YAML,
                        "kafka: \n"
                                + "  image: something\n"
                                + "  env:\n"
                                + "  - name: FOO\n"
                                + "    value: bar\n"
                                + "  brokerConfig:\n"
                                + "    \"auto.create.topics.enable\": false\n"
                                + "  listeners:\n"
                                + "     external:\n"
                                + "       auth:\n"
                                + "        jwksExpirySeconds: 3600\n"
                                + "        jwksRefreshSeconds: 900\n"
                                + "        jwksMinRefreshPauseSeconds: 5\n"))
                .build());

        Kafka kafkaOverride = overrideManager.getKafkaOverride(STRIMZI_CLUSTER_OPERATOR_VER);
        assertEquals(1, kafkaOverride.getEnv().size());
        assertEquals("FOO", kafkaOverride.getEnv().get(0).getName());

        assertEquals(1, kafkaOverride.getBrokerConfig().size());
        assertEquals(false, kafkaOverride.getBrokerConfig().get("auto.create.topics.enable"));

        assertEquals(1, kafkaOverride.getListeners().size());
        ListenerOverride external = kafkaOverride.getListeners().get("external");
        ListenerOverride.AuthOverride authentication = external.getAuth();
        assertEquals(3, authentication.getAdditionalProperties().size());
        assertEquals(3600, authentication.getAdditionalProperties().get("jwksExpirySeconds"));

    }

}
