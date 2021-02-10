package org.bf2.systemtest;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.Kafka;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.systemtest.framework.resource.ManagedKafkaResourceType;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ManagedKafkaST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(ManagedKafkaST.class);

    @BeforeAll
    void deployStrimzi() throws Exception {
        StrimziOperatorManager.installStrimzi(kube);
    }

    @AfterAll
    void clean() throws InterruptedException {
        FleetShardOperatorManager.deleteFleetShardOperator(kube);
        StrimziOperatorManager.uninstallStrimziClusterWideResources(kube);
    }

    @Test
    void testDeployManagedKafka(ExtensionContext extensionContext) throws Exception {
        String mkAppName = "my-mk";
        String testNamespace = "david-1234";

        var kafkacli = kube.client().customResources(Kafka.class, KafkaList.class);

        LOGGER.info("creating namespaces");
        resourceManager.createResource(extensionContext, new NamespaceBuilder().withNewMetadata().withName(testNamespace).endMetadata().build());

        FleetShardOperatorManager.deployFleetShardOperator(kube);

        LOGGER.info("Create managedkafka");


        ManagedKafka mk = new ManagedKafkaBuilder()
                .withMetadata(
                        new ObjectMetaBuilder()
                                .withNamespace(testNamespace)
                                .withName(mkAppName)
                                .build())
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewVersions()
                                    .withKafka("2.6.0")
                                .endVersions()
                                .withNewCapacity()
                                    .withNewIngressEgressThroughputPerSec("4Mi")
                                    .withNewMaxDataRetentionPeriod("P14D")
                                    .withNewMaxDataRetentionSize("100Gi")
                                    .withTotalMaxConnections(500)
                                    .withMaxPartitions(100)
                                .endCapacity()
                                .withNewEndpoint()
                                    .withNewBootstrapServerHost("")
                                    .withNewTls()
                                        .withNewCert("cert")
                                        .withNewKey("key")
                                    .endTls()
                                .endEndpoint()
                                .withNewOauth()
                                    .withClientId("clientID")
                                    .withNewTlsTrustedCertificate("cert")
                                    .withClientSecret("secret")
                                    .withUserNameClaim("userClaim")
                                    .withNewTokenEndpointURI("tokenEndpointURI")
                                    .withNewJwksEndpointURI("jwksEndpointURI")
                                    .withNewTokenEndpointURI("tokenUri")
                                    .withNewValidIssuerEndpointURI("issuer")
                                .endOauth()
                                .build())
                .build();

        resourceManager.createResource(extensionContext, mk);

        assertTrue(ManagedKafkaResourceType.getOperation().inAnyNamespace().list().getItems().size() > 0);

        assertTrue(kafkacli.inAnyNamespace().list().getItems().size() > 0);
        assertTrue(kube.client().pods().inNamespace(testNamespace).list().getItems().size() > 0);
        assertEquals("Running", ManagedKafkaResourceType.getCanaryPod(mk).getStatus().getPhase());
        assertEquals(3, ManagedKafkaResourceType.getKafkaPods(mk).size());
        assertEquals(3, ManagedKafkaResourceType.getZookeeperPods(mk).size());
    }
}
