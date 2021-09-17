package org.bf2.systemtest.integration;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.bf2.systemtest.framework.ExtensionContextParameterResolver;
import org.bf2.systemtest.framework.IndicativeSentences;
import org.bf2.systemtest.framework.ResourceManager;
import org.bf2.systemtest.framework.TestCallbackListener;
import org.bf2.systemtest.framework.TestExceptionCallbackListener;
import org.bf2.test.k8s.KubeClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Base systemtest class which should be derived in every ST class
 * Provides extensions and base callbacks from framework
 * Using this class cause avoiding test duplications
 */

@ExtendWith(TestCallbackListener.class)
@ExtendWith(ExtensionContextParameterResolver.class)
@ExtendWith(TestExceptionCallbackListener.class)
@DisplayNameGeneration(IndicativeSentences.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractST {
    protected KubeClient kube;
    protected ResourceManager resourceManager;

    @BeforeAll
    void init() {
        kube = KubeClient.getInstance();
        resourceManager = ResourceManager.getInstance();
    }

    public ConfigMap createCompanionConfigMap(String namespace) throws IOException {
        String url = AbstractST.class.getResource("/controllers/companion-templates-config-map.yaml").getPath();
        String f = Files.readString(Paths.get(url), StandardCharsets.UTF_8);
        ConfigMap cm = Serialization.unmarshal(f, ConfigMap.class);
        cm.getMetadata().setNamespace(namespace);
        return cm;
    }
}
