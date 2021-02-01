package org.bf2.operator.clients;

import java.util.Collections;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class AgentResourceClient {

	@Inject
    private KubernetesClient kubernetesClient;

	private MixedOperation<ManagedKafkaAgent, ManagedKafkaAgentList, Resource<ManagedKafkaAgent>> agentClient;

	private boolean ready = false;

    void onStart(@Observes StartupEvent ev) {
    	this.agentClient = kubernetesClient.customResources(ManagedKafkaAgent.class, ManagedKafkaAgentList.class);
    	this.ready = true;
    }

    public ManagedKafkaAgent create(ManagedKafkaAgent resource) {
    	if (!isReady()) {
    		throw new IllegalStateException("client not initialized yet..");
    	}
        return agentClient.inNamespace(resource.getMetadata().getNamespace()).createOrReplace(resource);
    }

    public List<ManagedKafkaAgent> list(String namespace) {
    	if (isReady()) {
    		return agentClient.inNamespace(namespace).list().getItems();
    	}
    	return Collections.emptyList();
    }

    private boolean isReady() {
    	return ready;
    }
}
