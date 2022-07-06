package org.bf2.sync;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import org.bf2.common.ManagedKafkaResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.controlplane.ControlPlaneRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithKubernetesTestServer
@QuarkusTest
@TestProfile(MockSyncProfile.class)
public class PollerTest {

    static final String ID = "id";
    static final String CLUSTER_ID = "007";

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

    @Inject
    ManagedKafkaResourceClient managedKafkaClient;

    @AfterEach
    public void afterEach() {
        // the test resource is suite scoped, so we clean up after each test
        managedKafkaClient.list().forEach((mk)->managedKafkaClient.delete(mk.getMetadata().getNamespace(), mk.getMetadata().getName()));
    }

    @Test
    public void testAddDeleteReserved() {
        ManagedKafka managedKafka = exampleManagedKafka();
        managedKafka.getMetadata().setLabels(Map.of(ManagedKafka.DEPLOYMENT_TYPE, ManagedKafka.RESERVED_DEPLOYMENT_TYPE));

        Mockito.when(controlPlaneRestClient.getKafkaClusters(CLUSTER_ID)).thenReturn(new ManagedKafkaList(Collections.singletonList(managedKafka)));
        managedKafkaSync.syncKafkaClusters();

        List<ManagedKafka> items = lookup.getLocalManagedKafkas();
        assertEquals(1, items.size());
        assertFalse(items.get(0).getSpec().isDeleted());
        assertTrue(items.get(0).isReserveDeployment());

        Mockito.when(controlPlaneRestClient.getKafkaClusters(CLUSTER_ID)).thenReturn(new ManagedKafkaList(Collections.emptyList()));
        managedKafkaSync.syncKafkaClusters();
        items = lookup.getLocalManagedKafkas();
        assertTrue(items.isEmpty());
    }

    @Test
    public void testAddDelete() {
        ManagedKafka managedKafka = exampleManagedKafka();

        List<ManagedKafka> items = lookup.getLocalManagedKafkas();
        assertEquals(0, items.size());

        assertNull(controlPlane.getDesiredState(ControlPlane.managedKafkaKey(managedKafka)));

        Mockito.when(controlPlaneRestClient.getKafkaClusters(CLUSTER_ID)).thenReturn(new ManagedKafkaList(Collections.singletonList(managedKafka)));
        managedKafkaSync.syncKafkaClusters();

        items = lookup.getLocalManagedKafkas();
        assertEquals(1, items.size());
        assertFalse(items.get(0).getSpec().isDeleted());

        // even though an annotation and other kube metadata have changed, the rest has not
        assertFalse(managedKafkaSync.changed(managedKafka, items.get(0)));
        // should detect a modified label
        assertTrue(managedKafkaSync.changed(new ManagedKafkaBuilder(managedKafka).editMetadata().addToLabels("key", "value").endMetadata().build(), items.get(0)));

        // should do nothing
        managedKafkaSync.syncKafkaClusters();
        items = lookup.getLocalManagedKafkas();
        assertEquals(1, items.size());
        assertTrue(items.get(0).getAnnotation(SecretManager.ANNOTATION_MASTER_SECRET_DIGEST).isPresent());

        // make sure the remote tracking is there and not marked as deleted
        assertFalse(controlPlane.getDesiredState(ControlPlane.managedKafkaKey(managedKafka)).getSpec().isDeleted());

        // try another placement - this shouldn't actually happen, should reject first and the original won't be there
        ManagedKafka nextPlacement = exampleManagedKafka();
        nextPlacement.setPlacementId("xyz");
        nextPlacement.getSpec().getVersions().setStrimzi("?");
        Mockito.when(controlPlaneRestClient.getKafkaClusters(CLUSTER_ID)).thenReturn(new ManagedKafkaList(Arrays.asList(managedKafka, nextPlacement)));
        managedKafkaSync.syncKafkaClusters();
        //should still be a single placement, and it should be the old one
        items = lookup.getLocalManagedKafkas();
        assertEquals(1, items.size());
        assertNotEquals("?", items.get(0).getSpec().getVersions().getStrimzi());

        // update the profile type
        managedKafka = new ManagedKafkaBuilder(managedKafka).editOrNewMetadata().addToLabels(ManagedKafka.PROFILE_TYPE, "anything").endMetadata().build();
        Mockito.when(controlPlaneRestClient.getKafkaClusters(CLUSTER_ID)).thenReturn(new ManagedKafkaList(Arrays.asList(managedKafka)));
        managedKafkaSync.syncKafkaClusters();
        items = lookup.getLocalManagedKafkas();
        // should still be one instance, but it's profile type has been updated
        assertEquals(1, items.size());
        assertEquals("anything", items.get(0).getMetadata().getLabels().get(ManagedKafka.PROFILE_TYPE));

        // try to remove before marked as deleted, should not be successful
        Mockito.when(controlPlaneRestClient.getKafkaClusters(CLUSTER_ID)).thenReturn(new ManagedKafkaList());
        managedKafkaSync.syncKafkaClusters();
        items = lookup.getLocalManagedKafkas();
        assertEquals(1, items.size());

        Mockito.when(controlPlaneRestClient.getKafkaClusters(CLUSTER_ID)).thenReturn(new ManagedKafkaList(Arrays.asList(managedKafka, nextPlacement)));
        managedKafka.getSpec().setDeleted(true);
        managedKafkaSync.syncKafkaClusters();
        items = lookup.getLocalManagedKafkas();
        assertTrue(items.get(0).getSpec().isDeleted());
        // now the remote tracking should be marked as deleted
        assertTrue(controlPlane.getDesiredState(ControlPlane.managedKafkaKey(managedKafka)).getSpec().isDeleted());

        // final removal
        Mockito.when(controlPlaneRestClient.getKafkaClusters(CLUSTER_ID)).thenReturn(new ManagedKafkaList());
        managedKafkaSync.syncKafkaClusters();
        items = lookup.getLocalManagedKafkas();
        assertEquals(0, items.size());

        // remote tracking should be gone
        assertNull(controlPlane.getDesiredState(ControlPlane.managedKafkaKey(managedKafka)));

        // if it shows up again need to inform the control plane delete is still needed
        Mockito.when(controlPlaneRestClient.getKafkaClusters(CLUSTER_ID)).thenReturn(new ManagedKafkaList(Collections.singletonList(managedKafka)));
        managedKafkaSync.syncKafkaClusters();

        // expect there to be a status about the deletion
        ArgumentCaptor<Map<String, ManagedKafkaStatus>> statusCaptor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(controlPlaneRestClient).updateKafkaClustersStatus(Mockito.eq(CLUSTER_ID), statusCaptor.capture());
        Map<String, ManagedKafkaStatus> status = statusCaptor.getValue();
        assertEquals(1, status.size());
        assertEquals(1, status.get(ID).getConditions().size());
    }

    static ManagedKafka exampleManagedKafka() {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.setId(ID);
        return mk;
    }

}
