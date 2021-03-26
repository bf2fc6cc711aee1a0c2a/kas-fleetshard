package org.bf2.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import javax.inject.Inject;

import org.bf2.common.AgentResourceClient;
import org.bf2.operator.resources.v1alpha1.ClusterCapacityBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatusBuilder;
import org.bf2.sync.controlplane.ControlPlaneRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
@TestProfile(MockSyncProfile.class)
public class AgentPollerTest {

    static final String CLUSTER_ID = "007";

    @Inject
    AgentResourceClient client;

    @Inject
    ManagedKafkaAgentSync managedKafkaAgentSync;

    @InjectMock
    @RestClient
    ControlPlaneRestClient controlPlaneRestClient;

    @Inject
    DirectLocalLookup lookup;

    @Test
    public void testAddDelete() {
        assertNull(lookup.getLocalManagedKafkaAgent());

        ManagedKafkaAgent managedKafkaAgent = AgentResourceClient.getDummyInstance();

        Mockito.reset(controlPlaneRestClient);
        Mockito.when(controlPlaneRestClient.get(CLUSTER_ID)).thenReturn(managedKafkaAgent);

        managedKafkaAgentSync.loop(); // pick up the agent from the control plane

        ManagedKafkaAgent local = lookup.getLocalManagedKafkaAgent();
        local.setStatus(new ManagedKafkaAgentStatusBuilder()
                .withRemainingCapacity(new ClusterCapacityBuilder().withConnections(1000).build()).build());
        client.updateStatus(local);

        assertEquals("test-token", local.getSpec().getObservability().getAccessToken());
        assertEquals(AgentResourceClient.RESOURCE_NAME, local.getMetadata().getName());

        managedKafkaAgent.getSpec().getObservability().setAccessToken("abc");

        managedKafkaAgentSync.loop(); // pick up the update

        local = lookup.getLocalManagedKafkaAgent();

        assertEquals("abc", local.getSpec().getObservability().getAccessToken());
        assertEquals(1000, local.getStatus().getRemainingCapacity().getConnections());
    }

}