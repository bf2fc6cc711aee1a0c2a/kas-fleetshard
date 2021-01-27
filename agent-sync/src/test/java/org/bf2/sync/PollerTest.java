package org.bf2.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.bf2.operator.resources.v1alpha1.KafkaInstance;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;

/**
 * KubernetesMockServerTestResource is not useful for crud tests,
 * so we need to inject a different client / server
 */
@Singleton
class KubernetesClientProducer {

	private KubernetesServer server = new KubernetesServer(false, true);
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

	@Test
	public void testAdd() {
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

		assertEquals(0, client.customResources(ManagedKafka.class).list().getItems().size());

		managedKafkaSync.syncKafkaClusters(Arrays.asList(managedKafka));

		assertEquals(1, client.customResources(ManagedKafka.class).list().getItems().size());
	}

}