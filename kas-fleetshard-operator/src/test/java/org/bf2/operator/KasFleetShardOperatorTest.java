package org.bf2.operator;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.bf2.operator.clients.AbstractCustomResourceClient;
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

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.QuarkusProdModeTest;


@UseKubeMockServer(https = false, crud = true, port = 56565)
public class KasFleetShardOperatorTest {

    static KubernetesClient client;

    /**
     * Run operator and connect it into custom mock kube server
     */
    @RegisterExtension
    static final QuarkusProdModeTest config =
            new QuarkusProdModeTest()
                    .setArchiveProducer(
                            () ->
                                    ShrinkWrap.create(JavaArchive.class)
                                            .addClasses(
                                                    AbstractCustomResourceClient.class,
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
                                                    KasFleetShardOperator.class,
                                                    ConditionUtils.class,
                                                    InformerManager.class,
                                                    Main.class))
                    .setApplicationName("fleetshard-operator")
                    .setApplicationVersion("0.1-SNAPSHOT")
                    .setRuntimeProperties(Map.of(
                            "quarkus.kubernetes-client.master-url", "http://localhost:56565",
                            "quarkus.kubernetes-client.trust-certs", "true"))
                    .setRun(true);

    /**
     * Test of operator can start and connect to a kube server without error
     * CRD of managed kafka is already installed
     */
    @Test
    void testConnectOperatorToCluster() throws InterruptedException {
        Thread.sleep(5_000);
        assertFalse(config.getStartupConsoleOutput().contains("ERROR"));
    }
}
