package org.bf2.sync;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.Versions;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.controlplane.ControlPlaneRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
@TestProfile(MockSyncProfile.class)
public class PollerTest {

    private static final String PLACEMENT_ID = "pid";

    @Inject
    KubernetesClient client;

    @Inject
    ManagedKafkaSync managedKafkaSync;

    @InjectMock
    @RestClient
    ControlPlaneRestClient controlPlaneRestClient;

    @Inject
    ControlPlane controlPlane;

    @Inject
    DirectLocalLookup lookup;

    @Test
    public void testAddDelete() {
        ManagedKafka managedKafka = exampleManagedKafka();

        List<ManagedKafka> items = lookup.getLocalManagedKafkas();
        assertEquals(0, items.size());

        assertNull(controlPlane.getManagedKafka(PLACEMENT_ID));

        managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka), Runnable::run);

        items = lookup.getLocalManagedKafkas();
        assertEquals(1, items.size());
        assertFalse(items.get(0).getSpec().isDeleted());

        // should do nothing
        managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka), Runnable::run);
        items = lookup.getLocalManagedKafkas();
        assertEquals(1, items.size());

        // make sure the remote tracking is there and not marked as deleted
        assertFalse(controlPlane.getManagedKafka(PLACEMENT_ID).getSpec().isDeleted());

        managedKafka.getSpec().setDeleted(true);
        managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka), Runnable::run);
        items = lookup.getLocalManagedKafkas();
        assertTrue(items.get(0).getSpec().isDeleted());
        // now the remote tracking should be marked as deleted
        assertTrue(controlPlane.getManagedKafka(PLACEMENT_ID).getSpec().isDeleted());

        // final removal
        managedKafkaSync.syncKafkaClusters(Collections.emptyList(), Runnable::run);
        items = lookup.getLocalManagedKafkas();
        assertEquals(0, items.size());

        // remote tracking should be gone
        assertNull(controlPlane.getManagedKafka(PLACEMENT_ID));

        // if it shows up again need to inform the control plane delete is still needed
        managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka), Runnable::run);

        // expect there to be a status about the deletion
        ArgumentCaptor<Map<String, ManagedKafkaStatus>> statusCaptor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(controlPlaneRestClient).updateKafkaClustersStatus(Mockito.eq("007"), statusCaptor.capture());
        Map<String, ManagedKafkaStatus> status = statusCaptor.getValue();
        assertEquals(1, status.size());
        assertEquals(1, status.get(PLACEMENT_ID).getConditions().size());
    }

    private ManagedKafka exampleManagedKafka() {
        ManagedKafka managedKafka = new ManagedKafka();
        managedKafka.setKind("ManagedKafka");
        managedKafka.getMetadata().setNamespace("test");
        managedKafka.getMetadata().setName("name");
        managedKafka.setId(PLACEMENT_ID);
        ManagedKafkaSpec spec = new ManagedKafkaSpec();
        Versions versions = new Versions();
        versions.setKafka("2.2.2");
        spec.setVersions(versions);
        managedKafka.setSpec(spec);
        return managedKafka;
    }

}