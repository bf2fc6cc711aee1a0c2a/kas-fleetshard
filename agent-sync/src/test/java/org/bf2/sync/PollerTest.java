package org.bf2.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.Versions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;

/**
 * KubernetesMockServerTestResource is not useful for crud tests, so we need to
 * inject a different client / server
 */
@Singleton
class KubernetesClientProducer {

    private static KubernetesServer server = new KubernetesServer(false, true);
    private KubernetesClient client;

    @Produces
    public KubernetesClient kubernetesClient() {
        return client;
    }

    @PostConstruct
    void onStart() {
        server.before();
        System.setProperty(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY, "false");
        System.setProperty(Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY, "false");
        Config config = new ConfigBuilder().withTrustCerts(true).withNamespace("test").withHttp2Disable(true)
                .withMasterUrl(server.getClient().getMasterUrl().toString()).build();

        client = new DefaultKubernetesClient(config);
        // initialize with the crd
        client.load(PollerTest.class.getResourceAsStream("/META-INF/dekorate/kubernetes.yml")).get().forEach(crd -> {
            client.apiextensions().v1beta1().customResourceDefinitions()
                    .createOrReplace((CustomResourceDefinition) crd);
        });
    }

    @PreDestroy
    void onStop() {
        server.after();
    }

}

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
    ScopedControlPlanRestClient controlPlane;

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

        // need to inform the control plan delete is still needed
        managedKafkas.delete();
        Mockito.when(localLookup.getLocalManagedKafka(managedKafka)).thenReturn(null);
        managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka), Runnable::run);

        // expect there to be a status about the deletion
        ArgumentCaptor<ManagedKafkaStatus> statusCaptor = ArgumentCaptor.forClass(ManagedKafkaStatus.class);
        Mockito.verify(controlPlane).updateKafkaClusterStatus(statusCaptor.capture(), Mockito.eq("mycluster"));
        ManagedKafkaStatus status = statusCaptor.getValue();
        assertEquals(1, status.getConditions().size());
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