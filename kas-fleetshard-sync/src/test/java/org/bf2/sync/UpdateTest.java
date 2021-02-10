package org.bf2.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.sync.client.ManagedKafkaResourceClient;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.controlplane.ControlPlaneRestClient;
import org.bf2.sync.informer.InformerManager;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.exceptions.verification.WantedButNotInvoked;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
@TestProfile(MockSyncProfile.class)
public class UpdateTest {

    @InjectMock
    @RestClient
    ControlPlaneRestClient controlPlaneRestClient;

    @Inject
    ControlPlane controlPlane;

    @Inject
    ManagedKafkaResourceClient managedKafkaClient;

    @Inject
    InformerManager informerManager;

    @AfterEach
    public void afterEach() {
        // the test resource is suite scoped, so we clean up after each test
        managedKafkaClient.list().forEach((mk)->managedKafkaClient.delete(mk.getMetadata().getNamespace(), mk.getMetadata().getName()));
    }

    @Test
    public void testManagedKafkaInformer() throws InterruptedException {
        ManagedKafka managedKafka = PollerTest.exampleManagedKafka();
        managedKafka.setStatus(
                new ManagedKafkaStatusBuilder().addNewCondition().withStatus("Installing").endCondition().build());

        assertTrue(informerManager.getLocalManagedKafkas().isEmpty());

        managedKafka.getMetadata().setNamespace("test");
        managedKafkaClient.create(managedKafka);

        // wait to make sure we see the status update
        for (int i = 0; i < 500; i++) {
            try {
                getUpdates().getValue().get(PollerTest.PLACEMENT_ID);
                break;
            } catch (WantedButNotInvoked e) {
                Thread.sleep(10);
            }
        }
        assertNotNull(getUpdates().getValue().get(PollerTest.PLACEMENT_ID));

        for (int i = 0; i < 500; i++) {
            if (!informerManager.getLocalManagedKafkas().isEmpty()) {
                break;
            }
            Thread.sleep(10);
        }
        assertFalse(informerManager.getLocalManagedKafkas().isEmpty());
    }

    @Test
    public void testControlPlanUpdates() {
        ManagedKafka managedKafka = PollerTest.exampleManagedKafka();
        managedKafka.setStatus(
                new ManagedKafkaStatusBuilder().addNewCondition().withStatus("Installed").endCondition().build());

        controlPlane.updateKafkaClusterStatus(null, managedKafka);
        assertEquals("Installed", getUpdates().getValue().get(PollerTest.PLACEMENT_ID).getConditions().get(0).getStatus());

        // simulate a resync
        // for now we're just looking for equality
        Mockito.clearInvocations(controlPlaneRestClient);
        controlPlane.updateKafkaClusterStatus(managedKafka, managedKafka);
        // should be batched
        Mockito.verifyNoInteractions(controlPlaneRestClient);

        // clear the batch
        controlPlane.sendPendingStatusUpdates();
        ArgumentCaptor<Map<String, ManagedKafkaStatus>> statusCaptor = getUpdates();
        assertEquals("Installed", statusCaptor.getValue().get(PollerTest.PLACEMENT_ID).getConditions().get(0).getStatus());
    }

    private ArgumentCaptor<Map<String, ManagedKafkaStatus>> getUpdates() {
        ArgumentCaptor<Map<String, ManagedKafkaStatus>> statusCaptor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(controlPlaneRestClient).updateKafkaClustersStatus(Mockito.eq(PollerTest.CLUSTER_ID), statusCaptor.capture());
        return statusCaptor;
    }

}