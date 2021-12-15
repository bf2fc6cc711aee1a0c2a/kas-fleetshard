package org.bf2.operator.controllers;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
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

    @Test
    void capactityReporting() {
        ManagedKafkaAgent dummyInstance = ManagedKafkaAgentResourceClient.getDummyInstance();
        dummyInstance.getMetadata().setNamespace(agentClient.getNamespace());
        assertNull(dummyInstance.getStatus());
        agentClient.create(dummyInstance);

        InformerManager mockInformerManager = Mockito.mock(InformerManager.class, Mockito.RETURNS_DEEP_STUBS);
        QuarkusMock.installMockForType(mockInformerManager, InformerManager.class);

        // no nodes nor brokers
        mkaController.statusUpdateLoop();
        ManagedKafkaAgent agent = agentClient.getByName(agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
        assertNotNull(agent.getStatus());
        assertEquals(0, agent.getStatus().getClusterInfo().getRemainingBrokers());
        assertEquals(15, agent.getStatus().getNodeInfo().getWorkLoadMinimum());
        assertEquals(0, agent.getStatus().getNodeInfo().getCurrent());
        assertEquals(0.9375, agent.getStatus().getSizeInfo().getBrokersPerNode(), .001);

        Mockito.when(mockInformerManager.getKafkaCount()).thenReturn(16);
        Mockito.when(mockInformerManager.getNodeInformer().getList().size()).thenReturn(126);

        QuarkusMock.installMockForType(mockInformerManager, InformerManager.class);

        // again with some nodes and brokers
        mkaController.statusUpdateLoop();
        agent = agentClient.getByName(agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
        assertNotNull(agent.getStatus());
        assertEquals(56, agent.getStatus().getClusterInfo().getRemainingBrokers());
        assertEquals(67, agent.getStatus().getNodeInfo().getWorkLoadMinimum());
        assertEquals(126, agent.getStatus().getNodeInfo().getCurrent());
        assertEquals(0.9375, agent.getStatus().getSizeInfo().getBrokersPerNode(), .001);

        agentClient.delete(agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
    }

}
