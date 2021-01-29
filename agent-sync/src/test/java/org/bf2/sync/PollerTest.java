package org.bf2.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.Versions;
import org.bf2.sync.controlplane.ControlPlane;
import org.bf2.sync.informer.LocalLookup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
@TestProfile(MockSyncProfile.class)
public class PollerTest {

    @Inject
    KubernetesClient client;

    @InjectMock
    LocalLookup localLookup;

    @Inject
    ManagedKafkaSync managedKafkaSync;

    @InjectMock
    ControlPlane controlPlane;

    @Test
    public void testAddDelete() {
        ManagedKafka managedKafka = exampleManagedKafka();

        MixedOperation<ManagedKafka, KubernetesResourceList<ManagedKafka>, Resource<ManagedKafka>> managedKafkas = client
                .customResources(ManagedKafka.class);
        List<ManagedKafka> items = managedKafkas.list().getItems();
        assertEquals(0, items.size());

        managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka), Runnable::run);

        items = managedKafkas.list().getItems();
        assertEquals(1, items.size());
        assertFalse(items.get(0).getSpec().isDeleted());

        // so we don't have to wait for the informer to be updated, we'll just mock to a
        // new instance
        Mockito.when(localLookup.getLocalManagedKafka(managedKafka)).thenReturn(exampleManagedKafka());

        // should do nothing
        managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka), Runnable::run);
        items = managedKafkas.list().getItems();
        assertEquals(1, items.size());

        managedKafka.getSpec().setDeleted(true);
        managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka), Runnable::run);
        items = managedKafkas.list().getItems();
        assertTrue(items.get(0).getSpec().isDeleted());

        // need to inform the control plane delete is still needed
        managedKafkas.delete();
        Mockito.when(localLookup.getLocalManagedKafka(managedKafka)).thenReturn(null);
        managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka), Runnable::run);

        // expect there to be a status about the deletion
        ArgumentCaptor<ManagedKafkaStatus> statusCaptor = ArgumentCaptor.forClass(ManagedKafkaStatus.class);
        Mockito.verify(controlPlane).updateKafkaClusterStatus(statusCaptor.capture(), Mockito.eq("mycluster"));
        ManagedKafkaStatus status = statusCaptor.getValue();
        assertEquals(1, status.getConditions().size());

        //final removal
        managedKafkaSync.syncKafkaClusters(Collections.emptyList(), Runnable::run);
        items = managedKafkas.list().getItems();
        assertEquals(0, items.size());
    }

    private ManagedKafka exampleManagedKafka() {
        ManagedKafka managedKafka = new ManagedKafka();
        managedKafka.setKind("ManagedKafka");
        managedKafka.getMetadata().setNamespace("test");
        managedKafka.getMetadata().setName("name");
        managedKafka.setKafkaClusterId("mycluster");
        ManagedKafkaSpec spec = new ManagedKafkaSpec();
        Versions versions = new Versions();
        versions.setKafka("2.2.2");
        spec.setVersions(versions);
        managedKafka.setSpec(spec);
        return managedKafka;
    }

}