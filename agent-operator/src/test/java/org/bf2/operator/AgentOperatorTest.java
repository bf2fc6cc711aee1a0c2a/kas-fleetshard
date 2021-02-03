package org.bf2.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.QuarkusProdModeTest;
import org.bf2.operator.clients.KafkaResourceClient;
import org.bf2.operator.controllers.ManagedKafkaController;
import org.bf2.operator.events.DeploymentEvent;
import org.bf2.operator.events.DeploymentEventSource;
import org.bf2.operator.events.KafkaEvent;
import org.bf2.operator.events.KafkaEventSource;
import org.bf2.operator.operands.AdminServer;
import org.bf2.operator.operands.Canary;
import org.bf2.operator.operands.KafkaCluster;
import org.bf2.operator.operands.KafkaInstance;
import org.bf2.operator.operands.Operand;
import org.bf2.test.mock.UseKubeMockServer;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;


@UseKubeMockServer(https = false, crud = true, port = 56565)
public class AgentOperatorTest {

    static KubernetesClient client;

    @RegisterExtension
    static final QuarkusProdModeTest config =
            new QuarkusProdModeTest()
                    .setArchiveProducer(
                            () ->
                                    ShrinkWrap.create(JavaArchive.class)
                                            .addClasses(
                                                    KafkaResourceClient.class,
                                                    ManagedKafkaController.class,
                                                    DeploymentEvent.class,
                                                    DeploymentEventSource.class,
                                                    KafkaEvent.class,
                                                    KafkaEventSource.class,
                                                    AdminServer.class,
                                                    Canary.class,
                                                    KafkaCluster.class,
                                                    KafkaInstance.class,
                                                    Operand.class,
                                                    AgentOperator.class,
                                                    ConditionUtils.class,
                                                    InformerManager.class,
                                                    Main.class))
                    .setApplicationName("agent-operator")
                    .setApplicationVersion("0.1-SNAPSHOT")
                    .setRuntimeProperties(Map.of(
                            "quarkus.kubernetes-client.master-url", "http://localhost:56565",
                            "quarkus.kubernetes-client.trust-certs", "true"))
                    .setRun(true);

    @Test
    void testConnectOperatorToCluster() throws InterruptedException {
        Thread.sleep(5_000);
        assertFalse(config.getStartupConsoleOutput().contains("ERROR"));
    }
}
