package org.bf2.sync;

import java.util.Arrays;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.KubernetesMockServerTestResource;
import io.quarkus.test.kubernetes.client.MockServer;

@QuarkusTestResource(KubernetesMockServerTestResource.class)
@QuarkusTest
@TestProfile(MockSyncProfile.class)
public class PollerTest {
	
	@MockServer
    KubernetesMockServer mockServer;

	@InjectMock
	LocalLookup localLookup;
	
	@Inject
	ManagedKafkaSync managedKafkaSync;

	@Test
	public void testAdd() {
		ManagedKafka managedKafka = new ManagedKafka();
		managedKafka.getMetadata().setNamespace("x");
		managedKafka.getMetadata().setName("name");

		Mockito.when(localLookup.getLocalManagedKafka(managedKafka)).thenReturn(null);

		//should add, just an exception for now
		Assertions.assertThrows(AssertionError.class,
				() -> managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka)));
	}

}