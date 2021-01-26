package org.bf2.sync;

import java.util.Arrays;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.KafkaInstance;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.kubernetes.client.KubernetesMockServerTestResource;

@QuarkusTestResource(KubernetesMockServerTestResource.class)
@QuarkusTest
@TestProfile(MockSyncProfile.class)
public class PollerTest {
	@InjectMock
	KubernetesClient client;
	
	@InjectMock
	LocalLookup localLookup;
	
	@Inject
	ManagedKafkaSync managedKafkaSync;

	@Disabled("seems to fail due to https://github.com/fabric8io/kubernetes-client/issues/2658 - it works against a non-mock")
	@Test
	public void testAdd() {
		client.load(PollerTest.class.getResourceAsStream("/META-INF/dekorate/kubernetes.yml")).get();
		
		ManagedKafka managedKafka = new ManagedKafka();
		managedKafka.setKind("ManagedKafka");
		managedKafka.getMetadata().setNamespace("test");
		managedKafka.getMetadata().setName("name");
		ManagedKafkaSpec spec = new ManagedKafkaSpec();
		KafkaInstance kafkaInstance = new KafkaInstance();
		kafkaInstance.setVersion("2.2.2");
		spec.setKafkaInstance(kafkaInstance);
		managedKafka.setSpec(spec);

		Mockito.when(localLookup.getLocalManagedKafka(managedKafka)).thenReturn(null);

		//should add, just an exception for now
		Assertions.assertThrows(AssertionError.class,
				() -> managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka)));
	}
	
}