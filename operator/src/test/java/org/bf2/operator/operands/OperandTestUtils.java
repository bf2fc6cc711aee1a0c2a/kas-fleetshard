package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.quarkus.test.junit.QuarkusMock;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.operator.managers.InformerManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.Profile;
import org.mockito.Mockito;

class OperandTestUtils {

    private OperandTestUtils() {

    }

    static void useNodeAffinity(ManagedKafka mk) {
        mk.setMetadata(
                new ObjectMetaBuilder(mk.getMetadata()).addToLabels(ManagedKafka.PROFILE_TYPE, "standard").build());

        ManagedKafkaAgent agent = ManagedKafkaAgentResourceClient.getDummyInstance();
        agent.getSpec().getCapacity().put("developer", new Profile());
        agent.getSpec().getCapacity().put("standard", new Profile());
        InformerManager manager = Mockito.mock(InformerManager.class);
        Mockito.when(manager.getLocalAgent()).thenReturn(agent);
        QuarkusMock.installMockForType(manager, InformerManager.class);
    }

}
