package org.bf2.systemtest.integration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.api.sync.SyncApiClient;
import org.bf2.systemtest.framework.ExtensionContextParameterResolver;
import org.bf2.systemtest.framework.IndicativeSentences;
import org.bf2.systemtest.framework.KeycloakInstance;
import org.bf2.systemtest.framework.ResourceManager;
import org.bf2.systemtest.framework.SystemTestEnvironment;
import org.bf2.systemtest.framework.TestCallbackListener;
import org.bf2.systemtest.framework.TestExceptionCallbackListener;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.KeycloakOperatorManager;
import org.bf2.test.k8s.KubeClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

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

    private static final Logger LOGGER = LogManager.getLogger(AbstractST.class);

    protected KubeClient kube;
    protected ResourceManager resourceManager;
    protected String syncEndpoint;
    protected KeycloakInstance keycloak;

    @BeforeAll
    void init() {
        kube = KubeClient.getInstance();
        resourceManager = ResourceManager.getInstance();
        syncEndpoint = FleetShardOperatorManager.createEndpoint(kube);
        LOGGER.info("Endpoint address {}", syncEndpoint);
        keycloak = SystemTestEnvironment.INSTALL_KEYCLOAK ? new KeycloakInstance(KeycloakOperatorManager.OPERATOR_NS) : null;
    }

    @BeforeEach
    void setup() throws Exception {
        // Remove any Kafkas left by previous tests
        SyncApiClient.deleteManagedKafkas(syncEndpoint);
    }
}
