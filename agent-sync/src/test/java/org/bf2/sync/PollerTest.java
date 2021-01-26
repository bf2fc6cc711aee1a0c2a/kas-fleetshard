package org.bf2.sync;

import java.util.Arrays;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

@QuarkusTest
public class PollerTest {

	@InjectMock
	LocalLookup localLookup;

	@Inject
	ManagedKafkaSync managedKafkaSync;

	@org.junit.jupiter.api.Test
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