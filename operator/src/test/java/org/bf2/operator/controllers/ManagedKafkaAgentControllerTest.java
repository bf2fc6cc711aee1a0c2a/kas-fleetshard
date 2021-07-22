package org.bf2.operator.controllers;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@WithKubernetesTestServer
@QuarkusTest
public class ManagedKafkaAgentControllerTest {

    @Inject
    ManagedKafkaAgentController mkaController;

    @Inject
    ManagedKafkaAgentResourceClient agentClient;

    @Test
    void shouldCreateStatus() {
        //try without an agent - nothing should happen
        mkaController.statusUpdateLoop();

        ManagedKafkaAgent dummyInstance = ManagedKafkaAgentResourceClient.getDummyInstance();
        dummyInstance.getMetadata().setNamespace(agentClient.getNamespace());
        assertNull(dummyInstance.getStatus());
        agentClient.create(dummyInstance);

        //should create the status even if
        mkaController.statusUpdateLoop();
        ManagedKafkaAgent agent = agentClient.getByName(agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
        assertNotNull(agent.getStatus());

        agentClient.delete(agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
    }

}
