package org.bf2.sync;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.controlplane.ControlPlaneRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @AfterEach
    public void afterEach() {
        // the test resource is suite scoped, so we clean up after each test
        managedKafkaClient.list()
                .forEach(
                        (mk) -> managedKafkaClient.delete(mk.getMetadata().getNamespace(), mk.getMetadata().getName()));
    }

    @Test
    public void testControlPlaneUpdates() {
        ManagedKafka managedKafka = PollerTest.exampleManagedKafka();
        managedKafka.getMetadata().setNamespace(managedKafka.getId());
        managedKafka.setStatus(
                new ManagedKafkaStatusBuilder().addNewCondition().withStatus("Installed").endCondition().build());
        managedKafkaClient.create(managedKafka);

        controlPlane.updateKafkaClusterStatus(PollerTest.exampleManagedKafka(), managedKafka);
        assertEquals("Installed", getUpdates().getValue().get(PollerTest.ID).getConditions().get(0).getStatus());

        // simulate a resync
        // for now we're just looking for equality
        Mockito.clearInvocations(controlPlaneRestClient);
        controlPlane.updateKafkaClusterStatus(managedKafka, managedKafka);
        // should not be sent
        Mockito.verifyNoInteractions(controlPlaneRestClient);

        // send everything
        controlPlane.sendResync();
        ArgumentCaptor<Map<String, ManagedKafkaStatus>> statusCaptor = getUpdates();
        assertEquals("Installed", statusCaptor.getValue().get(PollerTest.ID).getConditions().get(0).getStatus());
    }

    private ArgumentCaptor<Map<String, ManagedKafkaStatus>> getUpdates() {
        ArgumentCaptor<Map<String, ManagedKafkaStatus>> statusCaptor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(controlPlaneRestClient)
                .updateKafkaClustersStatus(Mockito.eq(PollerTest.CLUSTER_ID), statusCaptor.capture());
        return statusCaptor;
    }

}
